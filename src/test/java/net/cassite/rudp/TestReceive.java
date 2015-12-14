package net.cassite.rudp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class TestReceive {
        public static void main(String[] args) throws IOException {
                UDPSocket socket = new UDPSocket(23333);

                InputStream stream = null;
                while (stream == null) {
                        try {
                                Thread.sleep(1000);
                        } catch (InterruptedException e) {
                                e.printStackTrace();
                        }
                        try {
                                stream = socket.getInputStream(InetAddress.getLocalHost(), 23334);
                        } catch (IOException ignore) {
                        }
                }


                try {
                        while (true) {
                                List<Byte> list = new ArrayList<Byte>();
                                byte b;
                                while (-1 != (b = (byte) stream.read())) {
                                        list.add(b);
                                }
                                byte[] bytes = new byte[list.size()];
                                for (int i = 0; i < list.size(); ++i) {
                                        bytes[i] = list.get(i);
                                }
                                System.out.println(new String(bytes));
                        }
                } catch (IOException e) {
                        System.exit(0);
                }
        }
}
