package com.flashforge.farm.slic3r;

import android.util.Log;

import java.util.Arrays;
import java.util.List;

class OCCTLoader {
    private static final String TAG = "slic3r.OCCTLoader";

    private final static List<String> LIBS = Arrays.asList(
            "TKDESTEP",
            "TKXCAF",
            "TKLCAF",
            "TKCAF",
            "TKCDF",
            "TKV3d",
            "TKMesh",
            "TKXMesh",
            "TKBO",
            "TKPrim",
            "TKHLR",
            "TKShHealing",
            "TKTopAlgo",
            "TKGeomAlgo",
            "TKGeomBase",
            "TKBRep",
            "TKG3d",
            "TKG2d",
            "TKMath",
            "TKernel",
            "TKDE"
    );

    static void load() {
        for (String lib : LIBS) {
            try {
                System.loadLibrary(lib);
                Log.d(TAG, "Loaded lib" + lib + ".so");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load lib" + lib + ".so", t);
                throw t;
            }
        }
    }
}
