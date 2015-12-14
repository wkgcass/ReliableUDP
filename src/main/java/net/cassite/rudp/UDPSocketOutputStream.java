package net.cassite.rudp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * writes bytes into a packet, ends with -1
 */
public class UDPSocketOutputStream extends OutputStream {
        private Writer writer;
        private List<Byte> byteList = new ArrayList<Byte>();

        public UDPSocketOutputStream(Writer writer) {
                this.writer = writer;
        }

        @Override
        public void write(int b) throws IOException {
                if (b != -1) {
                        byteList.add((byte) b);
                } else {
                        byte[] bytes = new byte[byteList.size()];
                        for (int i = 0; i < byteList.size(); ++i) {
                                bytes[i] = byteList.get(i);
                        }
                        writer.write(bytes);
                }
        }
}
