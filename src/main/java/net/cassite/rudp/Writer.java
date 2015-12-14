package net.cassite.rudp;

import java.io.IOException;

/**
 * writes bytes into a packet
 */
public interface Writer {
        void write(byte[] bytes) throws IOException;
}
