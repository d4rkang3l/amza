package com.jivesoftware.os.amza.storage.binary;

import com.jivesoftware.os.amza.shared.WALReader;
import com.jivesoftware.os.amza.shared.WALWriter;
import java.io.File;

/**
 *
 * @author jonathan.colt
 */
public interface RowIO extends WALReader, WALWriter {

    void move(File destinationDir) throws Exception;

    long sizeInBytes() throws Exception;

    void flush() throws Exception;

    void close() throws Exception;
}
