package com.brentvatne.exoplayer;

import android.util.Base64;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptedDataSourceFactory implements DataSource.Factory {

  private Cipher mCipher;
  private SecretKeySpec mSecretKeySpec;
  private IvParameterSpec mIvParameterSpec;
  private TransferListener<? super DataSource> mTransferListener;

  public EncryptedDataSourceFactory(SecretKeySpec mSecretKeySpec,IvParameterSpec mIvParameterSpec, TransferListener<? super DataSource> listener) {
    this.mSecretKeySpec = mSecretKeySpec;
    this.mIvParameterSpec = mIvParameterSpec;
    mCipher =  getCipher();
    mTransferListener = listener;
  }

  private Cipher getCipher(){
    try {
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, mSecretKeySpec, mIvParameterSpec);
      return cipher;
    }catch (Exception e){
      e.printStackTrace();
    }
    return null;
  }



  @Override
  public EncryptedDataSource createDataSource() {
    return new EncryptedDataSource(mCipher, mSecretKeySpec, mIvParameterSpec, mTransferListener);
  }
}