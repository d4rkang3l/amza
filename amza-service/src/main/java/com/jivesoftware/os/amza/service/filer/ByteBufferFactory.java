package com.jivesoftware.os.amza.service.filer;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 */
public interface ByteBufferFactory {

    ByteBuffer allocate(int index, long size);

    ByteBuffer reallocate(int index, ByteBuffer oldBuffer, long newSize);

    long length();

    long nextLength(int index, long oldLength, long position);

    void close(ByteBufferBackedFiler[] filers) throws IOException;
}
