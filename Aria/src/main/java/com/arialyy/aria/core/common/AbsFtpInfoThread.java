/*
 * Copyright (C) 2016 AriaLyy(https://github.com/AriaLyy/Aria)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arialyy.aria.core.common;

import android.text.TextUtils;
import com.arialyy.aria.core.AriaManager;
import com.arialyy.aria.core.FtpUrlEntity;
import com.arialyy.aria.core.inf.AbsEntity;
import com.arialyy.aria.core.inf.AbsTaskEntity;
import com.arialyy.aria.core.upload.UploadEntity;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.Regular;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * Created by Aria.Lao on 2017/7/25.
 * 获取ftp文件夹信息
 */
public abstract class AbsFtpInfoThread<ENTITY extends AbsEntity, TASK_ENTITY extends AbsTaskEntity<ENTITY>>
    implements Runnable {

  private final String TAG = "AbsFtpInfoThread";
  protected ENTITY mEntity;
  protected TASK_ENTITY mTaskEntity;
  private int mConnectTimeOut;
  protected OnFileInfoCallback mCallback;
  protected long mSize = 0;
  protected String charSet = "UTF-8";
  private boolean isUpload = false;

  public AbsFtpInfoThread(TASK_ENTITY taskEntity, OnFileInfoCallback callback) {
    mTaskEntity = taskEntity;
    mEntity = taskEntity.getEntity();
    mConnectTimeOut =
        AriaManager.getInstance(AriaManager.APP).getDownloadConfig().getConnectTimeOut();
    mCallback = callback;
    if (mEntity instanceof UploadEntity) {
      isUpload = true;
    }
  }

  /**
   * 设置请求的远程文件路径
   *
   * @return 远程文件路径
   */
  protected abstract String setRemotePath();

  @Override public void run() {
    FTPClient client = null;
    try {
      client = createFtpClient();
      if (client == null) {
        ALog.e(TAG, String.format("任务【%s】失败", mTaskEntity.getUrlEntity().url));
        return;
      }
      String remotePath =
          new String(setRemotePath().getBytes(charSet), AbsFtpThreadTask.SERVER_CHARSET);
      FTPFile[] files = client.listFiles(remotePath);
      String s = client.getReplyString();
      ALog.i(TAG, s);
      boolean isExist = files.length != 0;
      if (!isExist && !isUpload) {
        failDownload(String.format("文件不存在，任务链接【%s】，remotePath：%s", mTaskEntity.getUrlEntity().url,
            remotePath), false);
        int i = remotePath.lastIndexOf(File.separator);
        FTPFile[] files1;
        if (i == -1) {
          files1 = client.listFiles();
        } else {
          files1 = client.listFiles(remotePath.substring(0, i + 1));
        }
        if (files1.length > 0) {
          ALog.i(TAG,
              String.format("路径【%s】下的文件列表 ===================================", setRemotePath()));
          for (FTPFile file : files1) {
            ALog.d(TAG, file.toString());
          }
          ALog.i(TAG,
              "================================= --end-- ===================================");
        } else {
          String msg = client.getReplyString();
          ALog.w(TAG, msg);
        }
        client.disconnect();
        return;
      }
      //为了防止编码错乱，需要使用原始字符串
      mSize = getFileSize(files, client, setRemotePath());
      int reply = client.getReplyCode();
      if (!FTPReply.isPositiveCompletion(reply)) {
        if (isUpload) {
          //服务器上没有该文件路径，表示该任务为新的上传任务
          mTaskEntity.setNewTask(true);
        } else {
          client.disconnect();
          failDownload(String.format("获取文件信息错误，错误码为：%s，msg：%s", reply, client.getReplyString()),
              true);
          return;
        }
      }
      mTaskEntity.setCode(reply);
      if (mSize != 0 && !isUpload) {
        mEntity.setFileSize(mSize);
      }
      mTaskEntity.update();
      onPreComplete(reply);
    } catch (IOException e) {
      failDownload(e.getMessage(), true);
    } finally {
      if (client != null) {
        try {
          client.disconnect();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * 检查文件是否存在
   *
   * @return {@code true}存在
   */
  private boolean checkFileExist(FTPFile[] ftpFiles, String fileName) {
    for (FTPFile ff : ftpFiles) {
      if (ff.getName().equals(fileName)) {
        return true;
      }
    }
    return false;
  }

  public void start() {
    new Thread(this).start();
  }

  protected void onPreComplete(int code) {

  }

  /**
   * 创建FTP客户端
   */
  private FTPClient createFtpClient() {
    FTPClient client = null;
    final FtpUrlEntity urlEntity = mTaskEntity.getUrlEntity();
    try {
      Pattern p = Pattern.compile(Regular.REG_IP_V4);
      Matcher m = p.matcher(urlEntity.hostName);
      if (m.find() && m.groupCount() > 0) {
        client = new FTPClient();
        InetAddress ip = InetAddress.getByName(urlEntity.hostName);
        client.setConnectTimeout(mConnectTimeOut);  // 连接10s超时
        client.connect(ip, Integer.parseInt(urlEntity.port));
        mTaskEntity.getUrlEntity().validAddr = ip;
      } else {
        DNSQueryThread dnsThread = new DNSQueryThread(urlEntity.hostName);
        dnsThread.start();
        dnsThread.join(mConnectTimeOut);
        InetAddress[] ips = dnsThread.getIps();
        client = connect(new FTPClient(), ips, 0, Integer.parseInt(urlEntity.port));
      }

      if (client == null) {
        failDownload("链接失败", false);
        return null;
      }

      boolean loginSuccess = true;
      if (urlEntity.needLogin) {
        try {
          if (TextUtils.isEmpty(urlEntity.account)) {
            loginSuccess = client.login(urlEntity.user, urlEntity.password);
          } else {
            loginSuccess = client.login(urlEntity.user, urlEntity.password, urlEntity.account);
          }
        } catch (IOException e) {
          ALog.e(TAG, client.getReplyString());
          return null;
        }
      }

      if (!loginSuccess) {
        failDownload("登录失败", false);
        return null;
      }

      int reply = client.getReplyCode();
      if (!FTPReply.isPositiveCompletion(reply)) {
        client.disconnect();
        failDownload(String.format("无法连接到ftp服务器，错误码为：%s，msg：%s", reply, client.getReplyString()),
            true);
        return null;
      }
      // 开启服务器对UTF-8的支持，如果服务器支持就用UTF-8编码
      charSet = "UTF-8";
      reply = client.sendCommand("OPTS UTF8", "ON");
      if (!TextUtils.isEmpty(mTaskEntity.getCharSet()) || (!FTPReply.isPositiveCompletion(reply)
          && reply != FTPReply.COMMAND_OK)) {
        ALog.i(TAG, "FTP 服务器不支持开启UTF8编码，尝试使用Aria手动设置的编码");
        charSet = mTaskEntity.getCharSet();
      }
      client.setControlEncoding(charSet);
      client.setDataTimeout(10 * 1000);
      client.enterLocalPassiveMode();
      client.setFileType(FTP.BINARY_FILE_TYPE);
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    return client;
  }

  /**
   * 连接到ftp服务器
   */
  private FTPClient connect(FTPClient client, InetAddress[] ips, int index, int port) {
    if (ips == null || ips.length == 0) {
      ALog.w(TAG, "无可用ip");
      return null;
    }
    try {
      client.setConnectTimeout(mConnectTimeOut);  //需要先设置超时，这样才不会出现阻塞
      client.connect(ips[index], port);
      mTaskEntity.getUrlEntity().validAddr = ips[index];
      return client;
    } catch (IOException e) {
      //e.printStackTrace();
      try {
        if (client.isConnected()) {
          client.disconnect();
        }
      } catch (IOException e1) {
        e1.printStackTrace();
      }
      if (index + 1 >= ips.length) {
        ALog.w(TAG, "遇到[ECONNREFUSED-连接被服务器拒绝]错误，已没有其他地址，链接失败");
        return null;
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e1) {
        e1.printStackTrace();
      }
      ALog.w(TAG, "遇到[ECONNREFUSED-连接被服务器拒绝]错误，正在尝试下一个地址");
      return connect(new FTPClient(), ips, index + 1, port);
    }
  }

  /**
   * 遍历FTP服务器上对应文件或文件夹大小
   *
   * @throws IOException 字符串编码转换错误
   */
  private long getFileSize(FTPFile[] files, FTPClient client, String dirName) throws IOException {
    long size = 0;
    String path = dirName + "/";
    for (FTPFile file : files) {
      if (file.isFile()) {
        size += file.getSize();
        handleFile(path + file.getName(), file);
      } else {
        String remotePath =
            new String((path + file.getName()).getBytes(charSet), AbsFtpThreadTask.SERVER_CHARSET);
        size += getFileSize(client.listFiles(remotePath), client, path + file.getName());
      }
    }
    return size;
  }

  /**
   * 处理FTP文件信息
   *
   * @param remotePath ftp服务器文件夹路径
   * @param ftpFile ftp服务器上对应的文件
   */
  protected void handleFile(String remotePath, FTPFile ftpFile) {
  }

  private void failDownload(String errorMsg, boolean needRetry) {
    ALog.e(TAG, errorMsg);
    if (mCallback != null) {
      mCallback.onFail(mEntity.getKey(), errorMsg, needRetry);
    }
  }

  /**
   * 获取可用IP的超时线程，InetAddress.getByName没有超时功能，需要自己处理超时
   */
  private static class DNSQueryThread extends Thread {

    private String hostName;
    private InetAddress[] ips;

    DNSQueryThread(String hostName) {
      this.hostName = hostName;
    }

    @Override public void run() {
      try {
        ips = InetAddress.getAllByName(hostName);
      } catch (UnknownHostException e) {
        e.printStackTrace();
      }
    }

    synchronized InetAddress[] getIps() {
      return ips;
    }
  }
}
