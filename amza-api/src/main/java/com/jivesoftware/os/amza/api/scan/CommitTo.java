package com.jivesoftware.os.amza.api.scan;

import com.jivesoftware.os.amza.api.stream.Commitable;

/**
 *
 * @author jonathan.colt
 */
public interface CommitTo {

    RowsChanged commit(byte[] prefix, Commitable commitable) throws Exception;
}
