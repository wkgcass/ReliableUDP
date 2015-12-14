package net.cassite.rudp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Scanner;

public class TestSend {
        public static void main(String[] args) throws IOException, InterruptedException {
                UDPSocket socket = new UDPSocket(23334);
                socket.connect(InetAddress.getLocalHost(), 23333);
                Thread.sleep(100);
                OutputStream stream = socket.getOutputStream(InetAddress.getLocalHost(), 23333);
                Scanner scanner = new Scanner(System.in);
                System.out.println("Type in the message to send. ':q!' means quit");
                while (true) {
                        String str = scanner.nextLine();
                        if (str.equals(":q!")) {
                                socket.close(InetAddress.getLocalHost(), 23333);
                                System.exit(0);
                        }
                        stream.write(str.getBytes());
                        stream.write(-1);
                }
        }
}
