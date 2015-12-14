package net.cassite.rudp;

/**
 * packet for data transfer. contains data and metadata
 */
public class DataPacket implements Comparable<DataPacket> {
        public long id;
        public int listeningPort;
        public String dataType;
        public byte[] data;

        public static DataPacket handShackingRequest(int port) {
                DataPacket packet = new DataPacket();
                packet.id = 1;
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_HANDSHAKING_REQUEST;
                return packet;
        }

        public static DataPacket ackHandShacking(int port) {
                DataPacket packet = new DataPacket();
                packet.id = 1;
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_ACK_OF_HANDSHAKING_REQUEST;
                return packet;
        }

        public static DataPacket establish(int port) {
                DataPacket packet = new DataPacket();
                packet.id = 2;
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_ESTABLISH_CONNECTION;
                return packet;
        }

        public static DataPacket ack(long id, int port) {
                DataPacket packet = new DataPacket();
                packet.id = id; // use packet.id as the id to check
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_ACK;
                return packet;
        }

        public static DataPacket ping(int port) {
                DataPacket packet = new DataPacket();
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_PING;
                return packet;
        }

        public static DataPacket pong(int port) {
                DataPacket packet = new DataPacket();
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_PONG;
                return packet;
        }

        public static DataPacket disconnect(int port) {
                DataPacket packet = new DataPacket();
                packet.listeningPort = port;
                packet.dataType = Constant.PACKET_TYPE_DISCONNECT;
                return packet;
        }

        @Override
        public int compareTo(DataPacket o) {
                return (int) (this.id - o.id);
        }
}
