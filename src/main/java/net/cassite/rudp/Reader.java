package net.cassite.rudp;

import java.io.IOException;

/**
 * reader
 */
public interface Reader {
        DataPacket read() throws IOException;
}
