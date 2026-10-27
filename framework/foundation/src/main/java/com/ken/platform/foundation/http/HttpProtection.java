package com.ken.platform.foundation.http;

/** 可选保护组件的生命周期边界；一个租约对应整个逻辑调用。 */
public interface HttpProtection {
  Lease enter(String resource);

  interface Lease extends AutoCloseable {
    void failed(Throwable failure);

    @Override
    void close();
  }

  Lease UNPROTECTED =
      new Lease() {
        public void failed(Throwable failure) {}

        public void close() {}
      };
  HttpProtection NONE = resource -> UNPROTECTED;
}
