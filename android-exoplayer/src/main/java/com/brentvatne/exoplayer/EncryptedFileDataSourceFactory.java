package com.brentvatne.exoplayer;

import android.content.Context;
import com.google.android.exoplayer2.upstream.DataSource;

public class EncryptedFileDataSourceFactory implements DataSource.Factory {

  Context mContext;

  public EncryptedFileDataSourceFactory(Context context) {
    mContext = context;
  }

  @Override
  public DataSource createDataSource() {
    return new EncryptedFileDataSource(mContext);
  }
}
