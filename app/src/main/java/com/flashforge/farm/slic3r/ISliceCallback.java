package com.flashforge.farm.slic3r;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Slice progress callback, invoked on a Binder thread in the app process.
 * Implementations MUST return immediately (post + return); blocking here
 * stalls the sandbox slice thread mid-job.
 */
public interface ISliceCallback extends IInterface {
    String DESCRIPTOR = "com.flashforge.farm.slic3r.ISliceCallback";
    int TRANSACTION_onProgress = IBinder.FIRST_CALL_TRANSACTION;

    void onProgress(int progress, String text) throws RemoteException;

    abstract class Stub extends Binder implements ISliceCallback {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ISliceCallback asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface inner = obj.queryLocalInterface(DESCRIPTOR);
            if (inner instanceof ISliceCallback) {
                return (ISliceCallback) inner;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_onProgress) {
                data.enforceInterface(DESCRIPTOR);
                int progress = data.readInt();
                String text = data.readString();
                onProgress(progress, text);
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements ISliceCallback {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void onProgress(int progress, String text) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(progress);
                    data.writeString(text);
                    remote.transact(TRANSACTION_onProgress, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
