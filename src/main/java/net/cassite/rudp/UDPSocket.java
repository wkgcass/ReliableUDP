package net.cassite.rudp;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * reliable udp socket
 */
public class UDPSocket implements Closeable {
        private static Logger logger = LoggerFactory.getLogger(UDPSocket.class);
        private static Gson gson = new Gson();
        private DatagramSocket receiver;
        private final int listeningPort;
        private static final int TIMEOUT = 60000;
        private static final int PING_PONG_INTERVAL = 30000;
        private static final int SENDING_INTERVAL = 10000;
        private static final int CHECK_INTERVAL = 12000;
        private final Encoder encoder = new Encoder();

        private class SocketRep {
                InetAddress address;
                int port;

                public SocketRep(InetAddress address, int port) {
                        this.address = address;
                        this.port = port;
                }

                @Override
                public boolean equals(Object o) {
                        if (this == o) return true;
                        if (o == null || getClass() != o.getClass()) return false;

                        SocketRep socketRep = (SocketRep) o;

                        return port == socketRep.port && address.equals(socketRep.address);
                }

                @Override
                public int hashCode() {
                        int result = address.hashCode();
                        result = 31 * result + port;
                        return result;
                }
        }

        private boolean closed;

        private class ConnectionHolder {
                private class Sending {
                        public DataPacket packet;
                        long lastSendTime;
                        int sendCount;
                }

                DatagramSocket sender;
                long expectedId;
                long lastResp;
                List<DataPacket> receivingBuffer = new Vector<DataPacket>();
                long nextSendId;
                List<Sending> sendingBuffer = new Vector<Sending>();

                void addSendingElement(DataPacket packet) {
                        Sending sending = new Sending();
                        sending.lastSendTime = 0;
                        sending.packet = packet;
                        sending.sendCount = 0;
                        sendingBuffer.add(sending);
                }
        }

        private Map<SocketRep, ConnectionHolder> connectionMap = new ConcurrentHashMap<SocketRep, ConnectionHolder>();
        private Map<SocketRep, ConnectionHolder> preparingMap = new ConcurrentHashMap<SocketRep, ConnectionHolder>();

        /**
         * encode and decode
         */
        private class Encoder {
                byte[] encode(byte[] bytes) {
                        CRC32 crc32 = new CRC32();
                        crc32.update(bytes);
                        long l = crc32.getValue();
                        String crc = "[" + Long.toHexString(l) + "]";
                        return (new String(bytes) + crc).getBytes();
                }

                /**
                 * verify and decode the bytes
                 *
                 * @param bytes bytes to be decoded
                 * @return decoded bytes
                 */
                byte[] decode(byte[] bytes) {
                        String str = new String(bytes);
                        String value = str.substring(0, str.lastIndexOf('}') + 1);
                        String post = str.substring(str.lastIndexOf('}') + 1);
                        post = post.substring(post.lastIndexOf(']'));
                        CRC32 crc32 = new CRC32();
                        crc32.update(value.getBytes());
                        if (Long.toHexString(crc32.getValue()).equals(post)) {
                                throw new IllegalArgumentException();
                        }
                        return value.getBytes();
                }
        }

        public UDPSocket(final int listenPort) throws IOException {
                this.listeningPort = listenPort;
                this.receiver = new DatagramSocket(listenPort);
                this.receiver.setSoTimeout(TIMEOUT);
                logger.info("listening port {}", listenPort);

                Thread receivingThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                                outer:
                                while (!closed) {
                                        try {
                                                Thread.sleep(10);
                                        } catch (InterruptedException ignore) {
                                        }
                                        byte[] bytes = new byte[1024];
                                        DatagramPacket dp = new DatagramPacket(bytes, bytes.length);
                                        try {
                                                receiver.receive(dp); // do real receive
                                                logger.debug("receiving a packet from {} with data {}", dp.getAddress().getHostAddress(), new String(bytes));
                                        } catch (SocketTimeoutException e) {
                                                logger.debug("datagram socket timeout, recreating one");
                                                if (!receiver.isClosed()) receiver.close();
                                                try {
                                                        receiver = new DatagramSocket(listeningPort);
                                                        receiver.setSoTimeout(TIMEOUT);
                                                        continue;
                                                } catch (SocketException e1) {
                                                        logger.error("error when trying to recreate a datagram socket - {}", e1);
                                                        throw new RuntimeException(e1);
                                                }
                                        } catch (IOException e) {
                                                logger.error("error when trying to receive a packet - {}", e);
                                                throw new RuntimeException(e);
                                        }
                                        // convert to DataPacket
                                        DataPacket packet;
                                        try {
                                                packet = transform(bytes);
                                        } catch (RuntimeException ignore) {
                                                // format mismatch, ignore
                                                continue;
                                        }
                                        if (packet == null) continue;
                                        InetAddress ip = dp.getAddress();
                                        int port = packet.listeningPort;
                                        SocketRep s = new SocketRep(ip, port);

                                        ConnectionHolder holder;
                                        if (preparingMap.containsKey(s)) {
                                                holder = preparingMap.get(s);
                                        } else if (connectionMap.containsKey(s)) {
                                                holder = connectionMap.get(s);
                                        } else {
                                                if (packet.dataType.equals(Constant.PACKET_TYPE_HANDSHAKING_REQUEST)) {
                                                        // handshaking request
                                                        holder = new ConnectionHolder();
                                                        holder.expectedId = 2;
                                                        holder.lastResp = System.currentTimeMillis();
                                                        holder.nextSendId = 2;
                                                        try {
                                                                holder.sender = new DatagramSocket();
                                                        } catch (SocketException e) {
                                                                logger.info("exception {} occurred while processing handshaking request", e);
                                                                continue;
                                                        }
                                                        preparingMap.put(s, holder);
                                                        byte[] bytes1 = transform(DataPacket.ackHandShacking(listeningPort));
                                                        try {
                                                                holder.sender.send(new DatagramPacket(bytes1, bytes1.length, s.address, s.port));
                                                                logger.info("ack to handshaking to {}:{} with data {}", s.address.getHostAddress(), s.port, new String(bytes1));
                                                        } catch (IOException e) {
                                                                logger.debug("failed to send ack to handshaking");
                                                        }
                                                }
                                                // else ignore
                                                continue;
                                        }
                                        holder.lastResp = System.currentTimeMillis();

                                        if (packet.dataType.equals(Constant.PACKET_TYPE_ACK)) {
                                                logger.debug("packet turned out to be a simple ack");
                                                // check ack
                                                Iterator<ConnectionHolder.Sending> it = holder.sendingBuffer.iterator();
                                                while (it.hasNext()) {
                                                        if (it.next().packet.id == packet.id) {
                                                                it.remove();
                                                                break;
                                                        }
                                                }
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_ESTABLISH_CONNECTION)) {
                                                logger.debug("packet turned out to be connection establish alert");
                                                // establish
                                                if (preparingMap.containsKey(s)) {
                                                        ConnectionHolder h = preparingMap.remove(s);
                                                        h.expectedId = 3;
                                                        h.nextSendId = 2;
                                                        h.lastResp = System.currentTimeMillis();
                                                        connectionMap.put(s, h);
                                                }
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_ACK_OF_HANDSHAKING_REQUEST)) {
                                                logger.debug("the packet turned out to be ack of handshaking request, which goes to receivingBuffer");
                                                holder.receivingBuffer.add(packet);
                                                holder.lastResp = System.currentTimeMillis();
                                                Collections.sort(holder.receivingBuffer);
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_PING)) {
                                                logger.debug("the packet turned out to be PING");
                                                holder.lastResp = System.currentTimeMillis();
                                                fill(s.address, s.port, DataPacket.pong(listeningPort));
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_PONG)) {
                                                logger.debug("the packet turned out to be PONG");
                                                holder.lastResp = System.currentTimeMillis();
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_DISCONNECT)) {
                                                logger.info("disconnect from {}:{}", s.address.getHostAddress(), s.port);
                                                connectionMap.remove(s);
                                                if (!holder.sender.isClosed()) holder.sender.close();
                                        } else if (packet.dataType.equals(Constant.PACKET_TYPE_DATA)) {
                                                logger.debug("the packet turned out to be data packet with data {}", new String(packet.data));
                                                // data
                                                holder.lastResp = System.currentTimeMillis();
                                                if (holder.expectedId > packet.id) {
                                                        // already retrieved
                                                        logger.debug("the packet has already been retrieved");
                                                        fill(s.address, s.port, DataPacket.ack(packet.id, listeningPort));
                                                        continue;
                                                } else {
                                                        // stored in buffer
                                                        logger.debug("the packet is stored in buffer");
                                                        for (DataPacket p : holder.receivingBuffer) {
                                                                if (p.id == packet.id) {
                                                                        fill(s.address, s.port, DataPacket.ack(packet.id, listeningPort));
                                                                        continue outer;
                                                                }
                                                        }
                                                }
                                                logger.debug("packet goes to receivingBuffer");
                                                fill(s.address, s.port, DataPacket.ack(packet.id, listeningPort));
                                                holder.receivingBuffer.add(packet);
                                                Collections.sort(holder.receivingBuffer);
                                        }
                                        // else ignore
                                }
                        }
                });
                receivingThread.start();

                Thread sendingThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                                while (!closed) {
                                        try {
                                                Thread.sleep(10);
                                        } catch (InterruptedException ignore) {
                                        }
                                        long current = System.currentTimeMillis();
                                        for (SocketRep s : connectionMap.keySet()) {
                                                ConnectionHolder holder = connectionMap.get(s);
                                                Iterator<ConnectionHolder.Sending> it = holder.sendingBuffer.iterator();
                                                while (it.hasNext()) {
                                                        ConnectionHolder.Sending sending = it.next();
                                                        if (sending.lastSendTime <= 0 || sending.lastSendTime + SENDING_INTERVAL < current) {
                                                                sending.lastSendTime = current;
                                                                ++sending.sendCount;
                                                                byte[] buf = transform(sending.packet);
                                                                try {
                                                                        holder.sender.send(new DatagramPacket(buf, buf.length, s.address, s.port));
                                                                        logger.debug("sending packet to {}:{} with data {}", s.address.getHostAddress(), s.port, new String(buf));
                                                                } catch (IOException ignored) {
                                                                        logger.info("Sending failed");
                                                                }
                                                        }
                                                        if (sending.packet.dataType.equals(Constant.PACKET_TYPE_ACK)
                                                                || sending.packet.dataType.equals(Constant.PACKET_TYPE_PING)
                                                                || sending.packet.dataType.equals(Constant.PACKET_TYPE_PONG)
                                                                || sending.packet.dataType.equals(Constant.PACKET_TYPE_DISCONNECT))
                                                                it.remove();
                                                }
                                        }
                                }
                        }
                });
                sendingThread.start();

                Thread timeoutAndPingPong = new Thread(new Runnable() {
                        @Override
                        public void run() {
                                while (!closed) {
                                        try {
                                                Thread.sleep(CHECK_INTERVAL);
                                        } catch (InterruptedException ignore) {
                                        }
                                        refreshConnectionMap();
                                }
                        }
                });
                timeoutAndPingPong.start();
        }

        private void checkNoConnection(InetAddress ip, int port) throws IOException {
                SocketRep s = new SocketRep(ip, port);
                if (preparingMap.containsKey(s)) {
                        throw new SocketException("connection to " + ip.getHostAddress() + ":" + port + " is being prepared");
                }
                if (connectionMap.containsKey(s)) {
                        throw new SocketException("connection to " + ip.getHostAddress() + ":" + port + " already exists");
                }
        }

        private byte[] transform(DataPacket dataPacket) {
                return encoder.encode(gson.toJson(dataPacket).getBytes());
        }

        private DataPacket transform(byte[] bytes) {
                try {
                        String str = new String(encoder.decode(bytes));
                        str = str.substring(0, str.lastIndexOf('}') + 1);
                        return gson.fromJson(str, DataPacket.class);
                } catch (Exception e) {
                        logger.info("bytes cannot be transformed into DataPacket, caused by {}", e);
                        return null;
                }
        }

        private void refreshConnectionMap0(long current, Iterator<SocketRep> it) {
                while (it.hasNext()) {
                        SocketRep s = it.next();
                        ConnectionHolder holder = connectionMap.get(s);
                        if (holder.lastResp + TIMEOUT < current) {
                                holder.sender.close();
                                logger.info("connection [{}:{}] timeout", s.address.getHostAddress(), s.port);
                                it.remove();
                        } else if (holder.lastResp + PING_PONG_INTERVAL + (new Random().nextInt(6000) - 3000) < current) {
                                fill(s.address, s.port, DataPacket.ping(listeningPort));
                                logger.info("sending PING");
                        }
                }
        }

        /**
         * remove timeout connections
         */
        private void refreshConnectionMap() {
                logger.debug("connection timeout check launched");
                if (connectionMap.isEmpty()) return;
                long current = new Date().getTime();
                refreshConnectionMap0(current, connectionMap.keySet().iterator());
                refreshConnectionMap0(current, preparingMap.keySet().iterator());
        }

        /**
         * request for socket
         *
         * @param ip   ip
         * @param port port
         * @return dataPacket or null if the (ip,port) is not in preparingMap and not in connectionMap
         */
        private DataPacket retrieve(InetAddress ip, int port) {
                SocketRep s = new SocketRep(ip, port);
                ConnectionHolder holder;
                while (true) {
                        if (!preparingMap.containsKey(s) && !connectionMap.containsKey(s)) return null;
                        try {
                                Thread.sleep(10);
                        } catch (InterruptedException ignore) {
                        }
                        if (preparingMap.containsKey(s)) {
                                holder = preparingMap.get(s);
                        } else if (connectionMap.containsKey(s)) {
                                holder = connectionMap.get(s);
                        } else {
                                return null;
                        }
                        if (!holder.receivingBuffer.isEmpty()) {
                                if (holder.receivingBuffer.get(0).id == holder.expectedId) break;
                        }
                }
                DataPacket packet = holder.receivingBuffer.remove(0);
                ++holder.expectedId;
                return packet;
        }

        private void fill(InetAddress ip, int port, DataPacket packet) {
                SocketRep s = new SocketRep(ip, port);
                if (connectionMap.containsKey(s)) {
                        ConnectionHolder holder = connectionMap.get(s);
                        if (packet.dataType.equals(Constant.PACKET_TYPE_DATA))
                                packet.id = holder.nextSendId++;
                        holder.addSendingElement(packet);
                } else {
                        throw new IllegalArgumentException(ip.getHostName() + ":" + port + " is not connected");
                }
        }

        public void connect(InetAddress ip, int port) throws IOException {
                checkNoConnection(ip, port);
                logger.info("Trying to connect {}:{}", ip.getHostAddress(), port);
                DatagramSocket sender = new DatagramSocket();

                // handshaking
                DataPacket dataPacket = DataPacket.handShackingRequest(listeningPort);
                byte[] buf = transform(dataPacket);
                // preparing
                SocketRep s = new SocketRep(ip, port);
                ConnectionHolder holder = new ConnectionHolder();
                holder.expectedId = 1;
                holder.lastResp = System.currentTimeMillis();
                holder.nextSendId = 2;
                holder.sender = sender;
                preparingMap.put(s, holder);
                sender.send(new DatagramPacket(buf, buf.length, ip, port)); // send handshake request
                logger.info("-- handshake request sent to {}:{} with data {}", ip.getHostName(), port, new String(buf));

                // get ack
                DataPacket ackPacket = retrieve(ip, port);
                if (ackPacket == null || !ackPacket.dataType.equals(Constant.PACKET_TYPE_ACK_OF_HANDSHAKING_REQUEST))
                        throw new SocketException("ACK expected, got " + (null == ackPacket ? null : ackPacket.dataType));
                if (ackPacket.id != 1) throw new SocketException("inconsistent id, 1 excepted, got " + ackPacket.id);
                holder.expectedId = 2;
                logger.info("-- handshake ack received");

                DataPacket establish = DataPacket.establish(listeningPort);
                byte[] establishBytes = transform(establish);
                sender.send(new DatagramPacket(establishBytes, establishBytes.length, ip, port));
                // remove from preparing set & add into map
                holder = preparingMap.remove(s);
                if (null != holder) {
                        connectionMap.put(s, holder);
                        holder.nextSendId = 3;
                        logger.info("-- connection established");
                } else {
                        logger.info("-- connecting attempt failed");
                }
                refreshConnectionMap();
        }

        public InputStream getInputStream(final InetAddress ip, final int port) throws IOException {
                SocketRep s = new SocketRep(ip, port);
                if (connectionMap.containsKey(s)) {
                        return new UDPSocketInputStream(new Reader() {
                                @Override
                                public DataPacket read() throws IOException {
                                        return retrieve(ip, port);
                                }
                        });
                } else {
                        throw new IOException("connection not established");
                }
        }

        public OutputStream getOutputStream(final InetAddress ip, final int port) throws IOException {
                final SocketRep s = new SocketRep(ip, port);
                if (connectionMap.containsKey(s)) {
                        return new UDPSocketOutputStream(new Writer() {
                                @Override
                                public void write(byte[] bytes) throws IOException {
                                        DataPacket packet = new DataPacket();
                                        packet.dataType = Constant.PACKET_TYPE_DATA;
                                        packet.data = bytes;
                                        packet.listeningPort = listeningPort;
                                        fill(ip, port, packet);
                                }
                        });
                } else {
                        throw new IOException("connection not established");
                }
        }

        @Override
        public void close() throws IOException {
                closed = true;
                Map<SocketRep, ConnectionHolder> map = connectionMap;
                connectionMap = new HashMap<SocketRep, ConnectionHolder>();
                for (SocketRep s : map.keySet()) {
                        ConnectionHolder holder = connectionMap.get(s);
                        holder.sender.close();
                }
                receiver.close();
        }

        public void close(InetAddress ip, int port) throws IOException {
                SocketRep s = new SocketRep(ip, port);
                if (connectionMap.containsKey(s)) {
                        ConnectionHolder holder = connectionMap.get(s);
                        byte[] buf = transform(DataPacket.disconnect(listeningPort));
                        holder.sender.send(new DatagramPacket(buf, buf.length, s.address, s.port));
                } else {
                        throw new IOException("connection not established");
                }
        }

        public boolean isConnected() {
                return !connectionMap.isEmpty();
        }
}
