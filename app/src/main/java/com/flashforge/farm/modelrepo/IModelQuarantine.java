package com.flashforge.farm.modelrepo;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

public interface IModelQuarantine extends IInterface {
    String DESCRIPTOR = "com.flashforge.farm.modelrepo.IModelQuarantine";
    int TRANSACTION_quarantine = IBinder.FIRST_CALL_TRANSACTION;

    Bundle quarantine(ParcelFileDescriptor fd, String fileName, long fileSize)
            throws RemoteException;

    abstract class Stub extends Binder implements IModelQuarantine {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IModelQuarantine asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface inner = obj.queryLocalInterface(DESCRIPTOR);
            if (inner instanceof IModelQuarantine) {
                return (IModelQuarantine) inner;
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
            if (code == TRANSACTION_quarantine) {
                data.enforceInterface(DESCRIPTOR);
                ParcelFileDescriptor fd = data.readFileDescriptor();
                String fileName = data.readString();
                long fileSize = data.readLong();
                Bundle result = quarantine(fd, fileName, fileSize);
                reply.writeNoException();
                reply.writeBundle(result);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IModelQuarantine {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public Bundle quarantine(ParcelFileDescriptor fd, String fileName, long fileSize)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeFileDescriptor(fd.getFileDescriptor());
                    data.writeString(fileName);
                    data.writeLong(fileSize);
                    remote.transact(TRANSACTION_quarantine, data, reply, 0);
                    reply.readException();
                    return reply.readBundle(Bundle.class.getClassLoader());
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
