package net.cassite.rudp;

import java.io.IOException;
import java.io.InputStream;

/**
 * input stream takes all bytes in 'one' packet
 */
public class UDPSocketInputStream extends InputStream {
        private Reader reader;
        private DataPacket packet;
        private int cursor = 0;

        public UDPSocketInputStream(Reader reader) {
                this.reader = reader;
        }

        @Override
        public int read() throws IOException {
                if (packet == null) {
                        packet = reader.read();
                }
                if (packet == null) throw new IOException("connection broken");
                if (packet.data == null || cursor == packet.data.length) {
                        packet = null;
                        return -1;
                }
                return packet.data[cursor++];
        }
}
