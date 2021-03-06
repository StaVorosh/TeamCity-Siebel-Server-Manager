/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package TeamCitySrvrmgr.server;

import TeamCitySrvrmgr.common.Util;
import com.sun.jna.Pointer;
import jetbrains.buildServer.log.Loggers;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Created by sbt-voroshilov-ss on 23.07.2015.
 */

public class ServerManagerInstance {
  private Process process;
  private boolean locked;
  private long lastCommandDate;
  private String enterpriseName;
  private String[] runTimeCommand;
  private BufferedWriter processInput;
  private InputStream processOutputStream;
  private int PID;
  /*TODO: debug*/
  private List<Map> processLog = new ArrayList<Map>();

  public ServerManagerInstance(String[] runTimeCommand, String enterpriseName) throws IOException, SrvrMgrException {
    setEnterpriseName(enterpriseName);
    setRunTimeCommand(runTimeCommand);
    startProcess();
  }

  private void startProcess() throws IOException, SrvrMgrException {
    ProcessBuilder builder = new ProcessBuilder(runTimeCommand);
    this.lastCommandDate = System.currentTimeMillis();
    try {
      builder.redirectErrorStream(true);
      this.process = builder.start();
    } catch (Exception ex) {
      throw new SrvrMgrException("Can't start process " + Arrays.toString(runTimeCommand));
    }
    this.locked = false;
    this.processInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
    this.processOutputStream = process.getInputStream();
    this.PID = getProcessId(process);
    /*TODO: debug*/
    processLog(runTimeCommand, "Input Command");

    byte[] inputData = new byte[1024];
    String result = "";
    int numBytes;

    while ((numBytes = readInputStreamWithTimeout(processOutputStream, inputData, 1000)) > 0 || !result.contains("srvrmgr>")) {
      if (numBytes > 0)
        result += new String(inputData, StandardCharsets.UTF_8);
      else {
        try {
          Thread.sleep(500);
        } catch (InterruptedException ex) {
        }
      }
      if (result.contains(("Fatal error"))) {
        this.process.destroy();
        throw new SrvrMgrException("Could not open connection to Siebel Gateway configuration");
      }
//      System.out.println(result);
      inputData = new byte[1024];
    }
    /*TODO: debug*/
    processLog(result, "Output after process creation");
  }

  public String executeCommand(String srvrmgrCommand) throws IOException, SrvrMgrException {

    String result = "";

    if (!this.getLocked())
      this.setLocked();
    else
      throw new IOException("Instance locked while trying to execute command " + srvrmgrCommand);

    byte[] inputData = new byte[1024];

    try {
      processInput.write(srvrmgrCommand);
      processInput.newLine();
      processInput.flush();
      /*TODO: debug*/
      processLog(srvrmgrCommand, "Input command");
    } catch (IOException ex) {
      if (!srvrmgrCommand.equals("exit")) {
        getProcessInput().close();
        getProcessOutputStream().close();
        getProcess().destroy();
        startProcess();
        result = executeCommand(srvrmgrCommand);
        Loggers.SERVER.error(Util.PLUGIN_NAME + ": Process has been killed. Restarting process", ex);
//          throw new SrvrMgrException("Process has been killed. Restarting process");
        return result;
      } else
        Loggers.SERVER.error(Util.PLUGIN_NAME + ": Process has been killed already", ex);
//          throw new SrvrMgrException("Process has been killed already");
    }

    while ((readInputStreamWithTimeout(processOutputStream, inputData, 1000)) > 0 || !result.contains("srvrmgr>")) {
      result += new String(inputData, StandardCharsets.UTF_8);
      inputData = new byte[1024];
      if (result.contains("Disconnecting from server"))
        break;
    }

    this.setLastCommandDate(System.currentTimeMillis());
    this.setUnLocked();
    /*TODO: debug*/
    processLog(result, getLastCommandDate(), "Command output");
    return result;
  }

  private static int getProcessId(Process process) {
    int PID = 0;
    if (process.getClass().getName().equals("java.lang.Win32Process") ||
            process.getClass().getName().equals("java.lang.ProcessImpl")) {
      try {
        Field f = process.getClass().getDeclaredField("handle");
        f.setAccessible(true);
        long handl = f.getLong(process);

        Kernel kernel = Kernel.INSTANCE;
        W32API.HANDLE handle = new W32API.HANDLE();
        handle.setPointer(Pointer.createConstant(handl));
        PID = kernel.GetProcessId(handle);
      } catch (Throwable e) {
        Loggers.SERVER.error("PID PROBLEM " + e);
      }
    }
    return PID;
  }

  private static int readInputStreamWithTimeout(InputStream is, byte[] b, int timeoutMillis) throws IOException {
    try {
      int bufferOffset = 0;
      long maxTimeMillis = System.currentTimeMillis() + timeoutMillis;
      while (System.currentTimeMillis() < maxTimeMillis && bufferOffset < b.length) {
        int readLength = java.lang.Math.min(is.available(), b.length - bufferOffset);
        int readResult = is.read(b, bufferOffset, readLength);
        if (readResult == -1)
          break;
        bufferOffset += readResult;
      }
      return bufferOffset;
    } catch (IOException ex) {
      Loggers.SERVER.error("Error reading stream. Stream closed: " + ex);
      return -1;
    }
  }

  public BufferedWriter getProcessInput() {
    return processInput;
  }

  public InputStream getProcessOutputStream() {
    return processOutputStream;
  }

  public Process getProcess() {
    return process;
  }

  public boolean getLocked() {
    return locked;
  }

  public long getLastCommandDate() {
    return lastCommandDate;
  }

  public String getEnterpriseName() {
    return enterpriseName;
  }

  public void setLocked() {
    this.locked = true;
  }

  public void setUnLocked() {
    this.locked = false;
  }

  public void setLastCommandDate(long lastCommandDate) {
    this.lastCommandDate = lastCommandDate;
  }

  public void setEnterpriseName(String enterpriseName) {
    this.enterpriseName = enterpriseName;
  }

  public void setRunTimeCommand(String[] runTimeCommand) {
    this.runTimeCommand = runTimeCommand;
  }

  public int getPID() {
    return PID;
  }

  private void processLog(Object obj, long time, String type){
    Map<String, Object> logItem = new HashMap<String, Object>();
    logItem.put("Time", time);
    logItem.put("logMsg", obj);
    logItem.put("msgType", type);
    this.processLog.add(logItem);
  }

  private void processLog(Object obj, String type){
    processLog(obj, System.currentTimeMillis(), type);
  }

  public List<Map> getProcessLog() {
    return processLog;
  }
}
