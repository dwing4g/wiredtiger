package jane.core;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * WiredTiger存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public final class StorageWtDB implements Storage
{
	private static final String				TABLE_NAME = "lsm:d";
	private static final StorageWtDB		_instance  = new StorageWtDB();
	private static final OctetsStream		_deleted   = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
	private final Map<Octets, OctetsStream>	_writeBuf  = Util.newConcurrentHashMap();	  // 提交过程中临时的写缓冲区
	private long							_db;										  // WiredTiger的数据库对象句柄
	private long							_dbWriteSession;
	private long							_dbWriteCursor;
	private final ThreadLocal<Long>			_dbReadCursors;
	private File							_dbFile;									  // 当前数据库的文件
	private volatile boolean				_writing;									  // 是否正在执行写操作

	static
	{
		System.load(new File(Const.levelDBNativePath, System.mapLibraryName("wt")).getAbsolutePath());
	}

	public native static long wiredtiger_open(String path, String option);

	public native static void wiredtiger_close(long handle);

	public native static long wiredtiger_open_session(long handle, String option);

	public native static boolean wiredtiger_open_table(long session, String name, String option);

	public native static long wiredtiger_open_cursor(long session, String option, String config);

	public native static byte[] wiredtiger_get(long cursor, byte[] key, int keylen); // return null for not found

	public native static int wiredtiger_write(long session, long cursor, Iterator<Entry<Octets, OctetsStream>> buf); // return 0 for ok

	public native static long wiredtiger_backup(long handle, String srcpath, String dstpath, String datetime); // return byte-size of copied data

	public native static long wiredtiger_iter_new(long handle, byte[] key, int keylen, int type); // type=0|1|2|3: <|<=|>=|>key

	public static native void wiredtiger_iter_delete(long iter);

	public static native byte[] wiredtiger_iter_next(long iter); // return cur-key(maybe null) and do next

	public static native byte[] wiredtiger_iter_prev(long iter); // return cur-key(maybe null) and do prev

	public static native byte[] wiredtiger_iter_value(long iter); // return cur-value(maybe null)

	public static native boolean wiredtiger_compact(long handle, byte[] keyFrom, int keyFromLen, byte[] keyTo, int keyToLen);

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String	   _tableName;
		private final int		   _tableId;
		private final int		   _tableIdLen;
		private final OctetsStream _tableIdCounter = new OctetsStream(6);
		private final V			   _stubV;

		public TableLong(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			_tableIdCounter.marshal1((byte)0xf1).marshalUInt(tableId); // 0xf1前缀用于idcounter
			_stubV = stubV;
		}

		private OctetsStream getKey(long k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + 9);
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			key.marshal(k);
			return key;
		}

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public V get(long k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(long k, V v)
		{
			_writeBuf.put(getKey(k), v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(long k)
		{
			_writeBuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if(from > to)
			{
				long t = from;
				from = to;
				to = t;
			}
			Octets keyFrom = getKey(from);
			Octets keyTo = getKey(to);
			long iter = 0;
			try
			{
				if(!reverse)
				{
					iter = wiredtiger_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = wiredtiger_iter_next(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!Helper.onWalkSafe(handler, keyOs.unmarshalLong())) return false;
					}
				}
				else
				{
					iter = wiredtiger_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = wiredtiger_iter_prev(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!Helper.onWalkSafe(handler, keyOs.unmarshalLong())) return false;
					}
				}
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if(iter != 0) wiredtiger_iter_delete(iter);
			}
			return true;
		}

		@Override
		public long getIdCounter()
		{
			OctetsStream val = dbget(_tableIdCounter);
			if(val == null) return 0;
			try
			{
				val.setExceptionInfo(true);
				return val.unmarshalLong();
			}
			catch(MarshalException e)
			{
				Log.log.error("unmarshal idcounter failed", e);
				return 0;
			}
		}

		@Override
		public void setIdCounter(long v)
		{
			if(v != getIdCounter())
				_writeBuf.put(_tableIdCounter, new OctetsStream(9).marshal(v));
		}
	}

	private abstract class TableBase<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		protected final String		 _tableName;
		protected final int			 _tableId;
		protected final int			 _tableIdLen;
		protected final OctetsStream _tableIdNext = new OctetsStream(5);
		protected final V			 _stubV;

		protected TableBase(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			if(tableId < Integer.MAX_VALUE)
				_tableIdNext.marshalUInt(tableId + 1);
			else
				_tableIdNext.marshal1((byte)0xf1);
			_stubV = stubV;
		}

		protected abstract OctetsStream getKey(K k);

		protected abstract boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException;

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public void put(K k, V v)
		{
			_writeBuf.put(getKey(k), v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(K k)
		{
			_writeBuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			Octets keyFrom = (from != null ? getKey(from) : new OctetsStream(5).marshalUInt(_tableId));
			Octets keyTo = (to != null ? getKey(to) : _tableIdNext);
			if(keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if(!reverse)
				{
					iter = wiredtiger_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = wiredtiger_iter_next(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!onWalk(handler, keyOs)) return false;
					}
				}
				else
				{
					iter = wiredtiger_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = wiredtiger_iter_prev(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!onWalk(handler, keyOs)) return false;
					}
				}
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if(iter != 0) wiredtiger_iter_delete(iter);
			}
			return true;
		}
	}

	private final class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(Octets k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + k.size());
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			key.append(k);
			return key;
		}

		@Override
		public V get(Octets k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		protected boolean onWalk(WalkHandler<Octets> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new Octets(k.array(), k.position(), k.remain()));
		}
	}

	private final class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(String k)
		{
			int tableIdLen = _tableIdLen;
			int n = k.length();
			OctetsStream key = new OctetsStream(tableIdLen + n * 3);
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			for(int i = 0; i < n; ++i)
				key.marshalUTF8(k.charAt(i));
			return key;
		}

		@Override
		public V get(String k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=\"" + k + '"');
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		protected boolean onWalk(WalkHandler<String> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new String(k.array(), k.position(), k.remain(), Const.stringCharsetUTF8));
		}
	}

	private final class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
	{
		private final K _stubK;

		protected TableBean(int tableId, String tableName, K stubK, V stubV)
		{
			super(tableId, tableName, stubV);
			_stubK = stubK;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected OctetsStream getKey(K k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + ((Bean<V>)k).initSize());
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			return ((Bean<V>)k).marshal(key);
		}

		@Override
		public V get(K k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		protected boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException
		{
			Bean<?> key = ((Bean<?>)_stubK).create();
			k.unmarshal(key);
			return Helper.onWalkSafe(handler, (K)key);
		}
	}

	public static StorageWtDB instance()
	{
		return _instance;
	}

	public StorageWtDB()
	{
		_dbReadCursors = new ThreadLocal<Long>()
		{
			@Override
			protected Long initialValue()
			{
				if(_db == 0)
					throw new Error("StorageWtDB.initReadCursor: db closed");
				long session = wiredtiger_open_session(_db, null);
				if(session == 0)
					throw new Error("StorageWtDB.initReadCursor: wiredtiger_open_session failed");
				if(!wiredtiger_open_table(session, TABLE_NAME, null))
					throw new Error("StorageWtDB.initReadCursor: wiredtiger_open_table failed");
				long cursor = wiredtiger_open_cursor(session, TABLE_NAME, null);
				if(cursor == 0)
					throw new Error("StorageWtDB.initReadCursor: wiredtiger_open_cursor failed");
				return cursor;
			}
		};
	}

	private OctetsStream dbget(Octets k)
	{
		if(_writing)
		{
			OctetsStream v = _writeBuf.get(k);
			if(v == _deleted) return null;
			if(v != null) return OctetsStream.wrap(v);
		}
		if(_db == 0) throw new IllegalStateException("db closed. key=" + k.dump());
		long cursor = _dbReadCursors.get().longValue();
		if(cursor == 0) throw new IllegalStateException("null cursor. key=" + k.dump());
		byte[] v = wiredtiger_get(cursor, k.array(), k.size());
		return v != null ? OctetsStream.wrap(v) : null;
	}

	@Override
	public String getFileSuffix()
	{
		return "wt";
	}

	@Override
	public void openDB(File file) throws IOException
	{
		close();
		_db = wiredtiger_open(file.getAbsolutePath(), String.format("create,cache_size=%dM", Const.levelDBCacheSize)); // ,transaction_sync=(enabled)
		if(_db == 0) throw new IOException("StorageWtDB.openDB: wiredtiger_open failed");
		_dbWriteSession = wiredtiger_open_session(_db, null);
		if(_dbWriteSession == 0)
		{
			wiredtiger_close(_db);
			_db = 0;
			throw new IOException("StorageWtDB.openDB: wiredtiger_open_session failed");
		}
		if(!wiredtiger_open_table(_dbWriteSession, TABLE_NAME, null))
		{
			_dbWriteSession = 0;
			wiredtiger_close(_db);
			_db = 0;
			throw new IOException("StorageWtDB.openDB: wiredtiger_open_table failed");
		}
		_dbWriteCursor = wiredtiger_open_cursor(_dbWriteSession, TABLE_NAME, null);
		if(_dbWriteCursor == 0)
		{
			_dbWriteSession = 0;
			wiredtiger_close(_db);
			_db = 0;
			throw new IOException("StorageWtDB.openDB: wiredtiger_open_cursor failed");
		}
		_dbFile = file;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		if(stubK instanceof Octets)
			return (Storage.Table<K, V>)new TableOctets<>(tableId, tableName, stubV);
		if(stubK instanceof String)
			return (Storage.Table<K, V>)new TableString<>(tableId, tableName, stubV);
		if(stubK instanceof Bean)
			return new TableBean<>(tableId, tableName, (K)stubK, stubV);
		throw new UnsupportedOperationException("unsupported key type: " +
				(stubK != null ? stubK.getClass().getName() : "null") + " for table: " + tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		return new TableLong<>(tableId, tableName, stubV);
	}

	public int getPutSize()
	{
		return _writeBuf.size();
	}

	@Override
	public void putBegin()
	{
		_writing = true;
	}

	@Override
	public void putFlush(boolean isLast)
	{
	}

	@Override
	public void commit()
	{
		if(_writeBuf.isEmpty())
		{
			_writing = false;
			return;
		}
		if(_db == 0)
		{
			Log.log.error("StorageWtDB.commit: db is closed");
			return;
		}
		int r = wiredtiger_write(_dbWriteSession, _dbWriteCursor, _writeBuf.entrySet().iterator());
		if(r != 0) Log.log.error("StorageWtDB.commit: wiredtiger_write failed({})", r);
		_writeBuf.clear();
		_writing = false;
	}

	@Override
	public void close()
	{
		commit();
		_writing = false;
		_dbFile = null;
		_dbWriteCursor = 0;
		_dbWriteSession = 0;
		if(_db != 0)
		{
			wiredtiger_close(_db);
			_db = 0;
		}
		_writeBuf.clear();
	}

	@Override
	public long backup(File fdst) throws IOException
	{
		return 0; //TODO
	}
}
