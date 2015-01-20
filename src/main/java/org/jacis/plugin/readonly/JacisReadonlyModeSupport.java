/*
 * Copyright (c) 2014 SSI Schaefer Noell GmbH
 */

package org.jacis.plugin.readonly;

/**
 * @author Jan Wiemer
 *
 *  Interface objects can implement to support switching them to read only mode and back.
 */
public interface JacisReadonlyModeSupport {

  public void switchToReadOnlyMode();

  public void switchToReadWriteMode();

}