package com.flashforge.farm.slic3r;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Binder interface for the isolated slice sandbox (issue #47).
 *
 * Hand-rolled Stub/Proxy (no AIDL): the whole
 * Slic3r/OCCT read + slice pipeline runs in an android:isolatedProcess
 * service, so a parser exploit lands in a permission-less process.
 * Files cross the boundary as FDs; the service opens them via
 * /proc/self/fd (see SandboxProto.fdPath).
 */
public interface ISliceSandbox extends IInterface {
    String DESCRIPTOR = "com.flashforge.farm.slic3r.ISliceSandbox";
    int TRANSACTION_slice = IBinder.FIRST_CALL_TRANSACTION;
    int TRANSACTION_read = IBinder.FIRST_CALL_TRANSACTION + 1;

    Bundle slice(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
            ParcelFileDescriptor outFd, Bundle params, ISliceCallback callback)
            throws RemoteException;

    /**
     * Parse a model FD in the sandbox and export the normalized 3MF to outFd.
     * Used for import-time parsing of untrusted files; params carry
     * KEY_BASENAME (String) and KEY_PLATE_ID (int).
     */
    Bundle read(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
            ParcelFileDescriptor outFd, Bundle params)
            throws RemoteException;

    abstract class Stub extends Binder implements ISliceSandbox {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ISliceSandbox asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface inner = obj.queryLocalInterface(DESCRIPTOR);
            if (inner instanceof ISliceSandbox) {
                return (ISliceSandbox) inner;
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
            if (code == TRANSACTION_slice) {
                data.enforceInterface(DESCRIPTOR);
                ParcelFileDescriptor modelFd = data.readFileDescriptor();
                ParcelFileDescriptor configFd = data.readFileDescriptor();
                ParcelFileDescriptor outFd = data.readFileDescriptor();
                Bundle params = data.readBundle(Bundle.class.getClassLoader());
                ISliceCallback callback =
                        ISliceCallback.Stub.asInterface(data.readStrongBinder());
                Bundle result = slice(modelFd, configFd, outFd, params, callback);
                reply.writeNoException();
                reply.writeBundle(result);
                return true;
            }
            if (code == TRANSACTION_read) {
                data.enforceInterface(DESCRIPTOR);
                ParcelFileDescriptor modelFd = data.readFileDescriptor();
                ParcelFileDescriptor configFd = data.readFileDescriptor();
                ParcelFileDescriptor outFd = data.readFileDescriptor();
                Bundle params = data.readBundle(Bundle.class.getClassLoader());
                Bundle result = read(modelFd, configFd, outFd, params);
                reply.writeNoException();
                reply.writeBundle(result);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements ISliceSandbox {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public Bundle slice(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
                    ParcelFileDescriptor outFd, Bundle params, ISliceCallback callback)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeFileDescriptor(modelFd.getFileDescriptor());
                    data.writeFileDescriptor(configFd.getFileDescriptor());
                    data.writeFileDescriptor(outFd.getFileDescriptor());
                    data.writeBundle(params);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    remote.transact(TRANSACTION_slice, data, reply, 0);
                    reply.readException();
                    return reply.readBundle(Bundle.class.getClassLoader());
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public Bundle read(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
                    ParcelFileDescriptor outFd, Bundle params)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeFileDescriptor(modelFd.getFileDescriptor());
                    data.writeFileDescriptor(configFd.getFileDescriptor());
                    data.writeFileDescriptor(outFd.getFileDescriptor());
                    data.writeBundle(params);
                    remote.transact(TRANSACTION_read, data, reply, 0);
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
