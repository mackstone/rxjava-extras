package com.github.davidmoten.rx.internal.operators;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import com.github.davidmoten.rx.buffertofile.DataSerializer;
import com.github.davidmoten.util.Preconditions;

class FileBasedSPSCQueue<T> implements QueueWithResources<T> {

	int readBufferPosition = 0;
	int readPosition = 0;
	final byte[] readBuffer;
	int readBufferLength = 0;
	final byte[] writeBuffer;
	final File file;
	final DataSerializer<T> serializer;
	final AtomicLong size;
	volatile int writePosition;
	volatile int writeBufferPosition;
	final Object writeLock = new Object();
	// guarded by accessLock
	private FileBasedSPSCQueue<T>.FileAccessor accessor;
	private final Object accessLock = new Object();
	private final DataOutputStream output;
	private final DataInputStream input;

	FileBasedSPSCQueue(int bufferSizeBytes, File file, DataSerializer<T> serializer) {
		Preconditions.checkArgument(bufferSizeBytes > 0, "bufferSizeBytes must be greater than zero");
		Preconditions.checkNotNull(file);
		Preconditions.checkNotNull(serializer);
		this.readBuffer = new byte[bufferSizeBytes];
		this.writeBuffer = new byte[bufferSizeBytes];
		try {
			file.getParentFile().mkdirs();
			file.createNewFile();
			this.file = file;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.accessor = new FileAccessor(file);
		this.serializer = serializer;
		this.size = new AtomicLong(0);
		this.output = new DataOutputStream(new QueueWriter());
		this.input = new DataInputStream(new QueueReader());
	}

	private final class FileAccessor {
		final RandomAccessFile fWrite;
		final RandomAccessFile fRead;

		FileAccessor(File file) {
			try {
				this.fWrite = new RandomAccessFile(file, "rw");
				this.fRead = new RandomAccessFile(file, "r");
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
		}

		public void close() {
			try {
				fWrite.close();
				fRead.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private final class QueueWriter extends OutputStream {

		@Override
		public void write(int b) throws IOException {
			if (writeBufferPosition < writeBuffer.length) {
				writeBuffer[writeBufferPosition] = (byte) b;
				writeBufferPosition++;
			} else {
				synchronized (writeLock) {
					accessor.fWrite.seek(writePosition);
					accessor.fWrite.write(writeBuffer);
					writeBuffer[0] = (byte) b;
					writeBufferPosition = 1;
					writePosition += writeBuffer.length;
				}
			}
		}

	}


	private final class QueueReader extends InputStream {

		@Override
		public int read() throws IOException {
			if (size.get() == 0) {
				throw new EOFException();
			} else {
				if (readBufferPosition < readBufferLength) {
					byte b = readBuffer[readBufferPosition];
					readBufferPosition++;
					return toUnsignedInteger(b);
				} else {
					// before reading more we see if we can emit directly from
					// the writeBuffer by checking if the read position is past
					// the write position
					while (true) {
						int wp;
						int wbp;
						synchronized (writeLock) {
							wp = writePosition;
							wbp = writeBufferPosition;
						}
						int over = wp - readPosition;
						if (over > 0) {
							//read position is past the write position
							readBufferLength = Math.min(readBuffer.length, over);
							synchronized (accessLock) {
								if (accessor == null) {
									accessor = new FileAccessor(file);
								}
								accessor.fRead.seek(readPosition);
								accessor.fRead.read(readBuffer, 0, readBufferLength);
							}
							readPosition += readBufferLength;
							readBufferPosition = 1;
							return toUnsignedInteger(readBuffer[0]);
						} else {
							//read position is not past the write position
							int index = -over;
							if (index >= writeBuffer.length) {
								throw new EOFException();
							} else {
								int b = toUnsignedInteger(writeBuffer[index]);
								final boolean writeBufferUnchanged;
								synchronized (writeLock) {
									writeBufferUnchanged = wp == writePosition && wbp == writeBufferPosition;
								}
								if (writeBufferUnchanged) {
									readPosition++;
									return b;
								}
							}
						}
					}
				}
			}
		}
	}

	private static int toUnsignedInteger(byte b) {
		return (int) b & 0x000000FF;
	}

	@Override
	public void unsubscribe() {
		synchronized (accessLock) {
			if (accessor != null) {
				accessor.close();
				accessor = null;
			}
		}
		if (!file.delete()) {
			throw new RuntimeException("could not delete file " + file);
		}
	}

	@Override
	public boolean isUnsubscribed() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean offer(T t) {
		try {
			serializer.serialize(output, t);
			size.incrementAndGet();
			return true;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public T poll() {
		try {
			T t = serializer.deserialize(input, Integer.MAX_VALUE);
			size.decrementAndGet();
			return t;
		} catch (EOFException e) {
			return null;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isEmpty() {
		return size.get() == 0;
	}

	@Override
	public void freeResources() {
		synchronized (accessLock) {
			if (accessor != null) {
				accessor.close();
			}
			accessor = null;
		}
	}

	@Override
	public T element() {
		throw new UnsupportedOperationException();
	}

	@Override
	public T peek() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean add(T e) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T remove() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean contains(Object o) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Iterator<T> iterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Object[] toArray() {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("hiding")
	@Override
	public <T> T[] toArray(T[] a) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean remove(Object o) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void clear() {
		throw new UnsupportedOperationException();
	}

}