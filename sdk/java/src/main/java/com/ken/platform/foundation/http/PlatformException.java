package com.ken.platform.foundation.http;

/** 跨语言稳定错误；异常消息不包含凭据、连接串或上游正文。 */
public class PlatformException extends RuntimeException {
  private final String code;
  private final int status;

  public PlatformException(String code, String message, int status) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }
}
