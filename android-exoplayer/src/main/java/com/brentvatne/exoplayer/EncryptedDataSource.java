package com.brentvatne.exoplayer;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public final class EncryptedDataSource implements DataSource {

  private final TransferListener<? super EncryptedDataSource> mTransferListener;
  private StreamingCipherInputStream mInputStream;
  private Uri mUri;
  private long mBytesRemaining;
  private boolean mOpened;
  private Cipher mCipher;
  private SecretKeySpec mSecretKeySpec;
  private IvParameterSpec mIvParameterSpec;

  public EncryptedDataSource(Cipher cipher, SecretKeySpec secretKeySpec, IvParameterSpec ivParameterSpec, TransferListener<? super EncryptedDataSource> listener) {
    mCipher = cipher;
    mSecretKeySpec = secretKeySpec;
    mIvParameterSpec = ivParameterSpec;
    mTransferListener = listener;
  }

  @Override
  public long open(DataSpec dataSpec) throws EncryptedFileDataSourceException {
    if (mOpened) {
      return mBytesRemaining;
    }
    mUri = dataSpec.uri;
    try {
      setupInputStream();
      skipToPosition(dataSpec);
      computeBytesRemaining(dataSpec);
    } catch (IOException e) {
      throw new EncryptedFileDataSourceException(e);
    }
    mOpened = true;
    if (mTransferListener != null) {
      mTransferListener.onTransferStart(this, dataSpec);
    }
    return mBytesRemaining;
  }

  private void setupInputStream() throws FileNotFoundException {
    File encryptedFile = new File(mUri.getPath());
    FileInputStream fileInputStream = new FileInputStream(encryptedFile);
    mInputStream = new StreamingCipherInputStream(fileInputStream, mCipher, mSecretKeySpec, mIvParameterSpec);
  }

  private void skipToPosition(DataSpec dataSpec) throws IOException {
    mInputStream.forceSkip(dataSpec.position);
  }

  private void computeBytesRemaining(DataSpec dataSpec) throws IOException {
    if (dataSpec.length != C.LENGTH_UNSET) {
      mBytesRemaining = dataSpec.length;
    } else {
      mBytesRemaining = mInputStream.available();
      if (mBytesRemaining == Integer.MAX_VALUE) {
        mBytesRemaining = C.LENGTH_UNSET;
      }
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws EncryptedFileDataSourceException {
    if (readLength == 0) {
      return 0;
    } else if (mBytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int bytesToRead = getBytesToRead(readLength);
    int bytesRead;
    try {
      bytesRead = mInputStream.read(buffer, offset, bytesToRead);
    } catch (IOException e) {
      throw new EncryptedFileDataSourceException(e);
    }
    if (bytesRead == -1) {
      if (mBytesRemaining != C.LENGTH_UNSET) {
        throw new EncryptedFileDataSourceException(new EOFException());
      }
      return C.RESULT_END_OF_INPUT;
    }
    if (mBytesRemaining != C.LENGTH_UNSET) {
      mBytesRemaining -= bytesRead;
    }
    if (mTransferListener != null) {
      mTransferListener.onBytesTransferred(this, bytesRead);
    }
    return bytesRead;
  }

  private int getBytesToRead(int bytesToRead) {
    if (mBytesRemaining == C.LENGTH_UNSET) {
      return bytesToRead;
    }
    return (int) Math.min(mBytesRemaining, bytesToRead);
  }

  @Override
  public Uri getUri() {
    return mUri;
  }

  @Override
  public void close() throws EncryptedFileDataSourceException {
    mUri = null;
    try {
      if (mInputStream != null) {
        mInputStream.close();
      }
    } catch (IOException e) {
      throw new EncryptedFileDataSourceException(e);
    } finally {
      mInputStream = null;
      if (mOpened) {
        mOpened = false;
        if (mTransferListener != null) {
          mTransferListener.onTransferEnd(this);
        }
      }
    }
  }

  public static final class EncryptedFileDataSourceException extends IOException {
    public EncryptedFileDataSourceException(IOException cause) {
      super(cause);
    }
  }

  public static class StreamingCipherInputStream extends CipherInputStream {

    private static final int AES_BLOCK_SIZE = 16;

    private InputStream mUpstream;
    private Cipher mCipher;
    private SecretKeySpec mSecretKeySpec;
    private IvParameterSpec mIvParameterSpec;

    public StreamingCipherInputStream(InputStream inputStream, Cipher cipher, SecretKeySpec secretKeySpec, IvParameterSpec ivParameterSpec) {
      super(inputStream, cipher);
      mUpstream = inputStream;
      mCipher = cipher;
      mSecretKeySpec = secretKeySpec;
      mIvParameterSpec = ivParameterSpec;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return super.read(b, off, len);
    }

    public long forceSkip(long bytesToSkip) throws IOException {
      long skipped = mUpstream.skip(bytesToSkip);
      try {
        int skip = (int) (bytesToSkip % AES_BLOCK_SIZE);
        long blockOffset = bytesToSkip - skip;
        long numberOfBlocks = blockOffset / AES_BLOCK_SIZE;
        // from here to the next inline comment, i don't understand
        BigInteger ivForOffsetAsBigInteger = new BigInteger(1, mIvParameterSpec.getIV()).add(BigInteger.valueOf(numberOfBlocks));
        byte[] ivForOffsetByteArray = ivForOffsetAsBigInteger.toByteArray();
        IvParameterSpec computedIvParameterSpecForOffset;
        if (ivForOffsetByteArray.length < AES_BLOCK_SIZE) {
          byte[] resizedIvForOffsetByteArray = new byte[AES_BLOCK_SIZE];
          System.arraycopy(ivForOffsetByteArray, 0, resizedIvForOffsetByteArray, AES_BLOCK_SIZE - ivForOffsetByteArray.length, ivForOffsetByteArray.length);
          computedIvParameterSpecForOffset = new IvParameterSpec(resizedIvForOffsetByteArray);
        } else {
          computedIvParameterSpecForOffset = new IvParameterSpec(ivForOffsetByteArray, ivForOffsetByteArray.length - AES_BLOCK_SIZE, AES_BLOCK_SIZE);
        }
        mCipher.init(Cipher.ENCRYPT_MODE, mSecretKeySpec, computedIvParameterSpecForOffset);
        byte[] skipBuffer = new byte[skip];
        mCipher.update(skipBuffer, 0, skip, skipBuffer);
        Arrays.fill(skipBuffer, (byte) 0);
      } catch (Exception e) {
        return 0;
      }
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return mUpstream.available();
    }

  }

}
