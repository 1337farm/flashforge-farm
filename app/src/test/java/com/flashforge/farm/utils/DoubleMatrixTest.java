package com.flashforge.farm.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class DoubleMatrixTest {

    private static final double DELTA = 1e-10;

    @Test
    public void testOrthoM_HappyPath() {
        double[] m = new double[16];
        double left = -1.0;
        double right = 1.0;
        double bottom = -1.0;
        double top = 1.0;
        double near = -1.0;
        double far = 1.0;

        DoubleMatrix.orthoM(m, 0, left, right, bottom, top, near, far);

        double[] expected = {
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, -1.0, 0.0,
            -0.0, -0.0, -0.0, 1.0
        };

        assertArrayEquals(expected, m, 1e-9);
    }

    @Test
    public void testOrthoM_HappyPathOffset() {
        double[] m = new double[20];
        double left = 0.0;
        double right = 100.0;
        double bottom = 0.0;
        double top = 50.0;
        double near = 1.0;
        double far = 10.0;

        DoubleMatrix.orthoM(m, 4, left, right, bottom, top, near, far);

        double[] expected = {
            0.0, 0.0, 0.0, 0.0, // offset
            0.02, 0.0, 0.0, 0.0,
            0.0, 0.04, 0.0, 0.0,
            0.0, 0.0, -0.2222222222222222, 0.0,
            -1.0, -1.0, -1.2222222222222223, 1.0
        };

        assertArrayEquals(expected, m, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrthoM_LeftEqualsRight() {
        double[] m = new double[16];
        DoubleMatrix.orthoM(m, 0, 1.0, 1.0, -1.0, 1.0, -1.0, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrthoM_BottomEqualsTop() {
        double[] m = new double[16];
        DoubleMatrix.orthoM(m, 0, -1.0, 1.0, 1.0, 1.0, -1.0, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrthoM_NearEqualsFar() {
        double[] m = new double[16];
        DoubleMatrix.orthoM(m, 0, -1.0, 1.0, -1.0, 1.0, 1.0, 1.0);
    }

    @Test
    public void testTransposeM_basic() {
        // Create a basic 4x4 matrix, numbered sequentially for easy tracking
        // Matrix in column-major order:
        // [ 1,  5,  9, 13]
        // [ 2,  6, 10, 14]
        // [ 3,  7, 11, 15]
        // [ 4,  8, 12, 16]
        double[] m = new double[]{
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16
        };

        // Expected transpose:
        // [ 1,  2,  3,  4]
        // [ 5,  6,  7,  8]
        // [ 9, 10, 11, 12]
        // [13, 14, 15, 16]
        double[] expectedTransposed = new double[]{
                1, 5, 9, 13,
                2, 6, 10, 14,
                3, 7, 11, 15,
                4, 8, 12, 16
        };

        double[] mTrans = new double[16];

        DoubleMatrix.transposeM(mTrans, 0, m, 0);

        assertArrayEquals(expectedTransposed, mTrans, DELTA);
    }

    @Test
    public void testTransposeM_withOffsets() {
        // Matrix in column-major order:
        // [ 1,  5,  9, 13]
        // [ 2,  6, 10, 14]
        // [ 3,  7, 11, 15]
        // [ 4,  8, 12, 16]

        // Let's create an input array with some padding at the start
        int mOffset = 3;
        double[] m = new double[]{
                -1, -1, -1, // offset padding
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
                -1, -1 // trailing padding
        };

        // Let's create an output array with some padding
        int mTransOffset = 2;
        double[] mTrans = new double[16 + mTransOffset + 2]; // 2 extra for trailing padding
        for (int i = 0; i < mTrans.length; i++) {
            mTrans[i] = -2; // Fill with -2 to verify padding isn't overwritten
        }

        // Expected transposed portion:
        // [ 1,  2,  3,  4]
        // [ 5,  6,  7,  8]
        // [ 9, 10, 11, 12]
        // [13, 14, 15, 16]
        double[] expectedTransposed = new double[]{
                -2, -2, // offset padding
                1, 5, 9, 13,
                2, 6, 10, 14,
                3, 7, 11, 15,
                4, 8, 12, 16,
                -2, -2 // trailing padding
        };

        DoubleMatrix.transposeM(mTrans, mTransOffset, m, mOffset);

        assertArrayEquals(expectedTransposed, mTrans, DELTA);
    }

    @Test
    public void testSetIdentityM_offset0() {
        double[] sm = new double[16];
        DoubleMatrix.setIdentityM(sm, 0);

        // Expected identity matrix in column-major or row-major (1D array)
        // 1 0 0 0
        // 0 1 0 0
        // 0 0 1 0
        // 0 0 0 1
        for (int i = 0; i < 16; i++) {
            if (i % 5 == 0) {
                assertEquals("Element at index " + i + " should be 1.0", 1.0, sm[i], 0.0);
            } else {
                assertEquals("Element at index " + i + " should be 0.0", 0.0, sm[i], 0.0);
            }
        }
    }

    @Test
    public void testSetIdentityM_withOffset() {
        double[] sm = new double[20];
        // Fill array with 9.0 to check if it gets overwritten
        for (int i = 0; i < 20; i++) {
            sm[i] = 9.0;
        }

        int offset = 3;
        DoubleMatrix.setIdentityM(sm, offset);

        // Check before offset
        for (int i = 0; i < offset; i++) {
            assertEquals("Element before offset should remain unchanged", 9.0, sm[i], 0.0);
        }

        // Check identity matrix
        for (int i = 0; i < 16; i++) {
            if (i % 5 == 0) {
                assertEquals("Element at index " + (offset + i) + " should be 1.0", 1.0, sm[offset + i], 0.0);
            } else {
                assertEquals("Element at index " + (offset + i) + " should be 0.0", 0.0, sm[offset + i], 0.0);
            }
        }

        // Check after identity matrix
        assertEquals("Element after matrix should remain unchanged", 9.0, sm[19], 0.0);
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void testSetIdentityM_arrayTooSmall() {
        double[] sm = new double[15]; // Requires 16
        DoubleMatrix.setIdentityM(sm, 0);
    }

    @Test
    public void testInvertIdentityMatrix() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);
        double[] mInv = new double[16];

        boolean inverted = DoubleMatrix.invertM(mInv, 0, m, 0);
        assertTrue("Identity matrix should be invertible", inverted);

        for (int i = 0; i < 16; i++) {
            assertEquals("Inverse of identity is identity", m[i], mInv[i], DELTA);
        }
    }

    @Test
    public void testInvertZeroMatrix() {
        double[] m = new double[16];
        double[] mInv = new double[16];

        boolean inverted = DoubleMatrix.invertM(mInv, 0, m, 0);
        assertFalse("Zero matrix should not be invertible", inverted);
    }

    @Test
    public void testInvertTypicalMatrix() {
        double[] m = {
            1, 2, 3, 4,
            5, 1, 6, 7,
            8, 9, 1, 2,
            3, 4, 5, 1
        };
        double[] mInv = new double[16];

        boolean inverted = DoubleMatrix.invertM(mInv, 0, m, 0);
        assertTrue("This specific matrix should be invertible", inverted);

        double[] result = new double[16];
        DoubleMatrix.multiplyMM(result, 0, m, 0, mInv, 0);

        double[] identity = new double[16];
        DoubleMatrix.setIdentityM(identity, 0);

        for (int i = 0; i < 16; i++) {
            assertEquals("M * M^-1 should equal Identity", identity[i], result[i], DELTA);
        }
    }

    @Test
    public void testRotateMIdentity() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        double[] rm = new double[16];

        // Rotate by 0 degrees around Z axis
        DoubleMatrix.rotateM(rm, 0, m, 0, 0, 0, 0, 1);

        // Should be identity
        assertArrayEquals(m, rm, DELTA);
    }

    @Test
    public void testRotateM90DegreesZ() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        double[] rm = new double[16];

        // Rotate by 90 degrees around Z axis
        DoubleMatrix.rotateM(rm, 0, m, 0, 90, 0, 0, 1);

        // Expected result for 90 degree Z rotation of identity matrix
        // [ cos(90) -sin(90)   0   0 ]
        // [ sin(90)  cos(90)   0   0 ]
        // [    0        0      1   0 ]
        // [    0        0      0   1 ]
        // In column-major order:
        // [0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]

        double[] expected = new double[] {
            0, 1, 0, 0,
            -1, 0, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        assertArrayEquals(expected, rm, DELTA);
    }

    @Test
    public void testRotateM90DegreesX() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        double[] rm = new double[16];

        // Rotate by 90 degrees around X axis
        DoubleMatrix.rotateM(rm, 0, m, 0, 90, 1, 0, 0);

        // Expected result for 90 degree X rotation of identity matrix
        // [ 1      0         0      0 ]
        // [ 0   cos(90)  -sin(90)   0 ]
        // [ 0   sin(90)   cos(90)   0 ]
        // [ 0      0         0      1 ]
        // In column-major order:
        // [1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1]

        double[] expected = new double[] {
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, -1, 0, 0,
            0, 0, 0, 1
        };

        assertArrayEquals(expected, rm, DELTA);
    }

    @Test
    public void testRotateM90DegreesY() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        double[] rm = new double[16];

        // Rotate by 90 degrees around Y axis
        DoubleMatrix.rotateM(rm, 0, m, 0, 90, 0, 1, 0);

        // Expected result for 90 degree Y rotation of identity matrix
        // [ cos(90)   0   sin(90)   0 ]
        // [    0      1      0      0 ]
        // [-sin(90)   0   cos(90)   0 ]
        // [    0      0      0      1 ]
        // In column-major order:
        // [0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1]

        double[] expected = new double[] {
            0, 0, -1, 0,
            0, 1, 0, 0,
            1, 0, 0, 0,
            0, 0, 0, 1
        };

        assertArrayEquals(expected, rm, DELTA);
    }

    @Test
    public void testRotateMInPlace() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        // In-place rotate by 90 degrees around X axis
        DoubleMatrix.rotateM(m, 0, 90, 1, 0, 0);

        double[] expected = new double[] {
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, -1, 0, 0,
            0, 0, 0, 1
        };

        assertArrayEquals(expected, m, DELTA);
    }

@Test
    public void testMultiplyMM_NullArguments() {
        double[] matrix = new double[16];

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(null, 0, matrix, 0, matrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(matrix, 0, null, 0, matrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(matrix, 0, matrix, 0, null, 0);
        });
    }

@Test
    public void testMultiplyMM_NegativeOffsets() {
        double[] matrix = new double[16];

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(matrix, -1, matrix, 0, matrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(matrix, 0, matrix, -1, matrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(matrix, 0, matrix, 0, matrix, -1);
        });
    }

@Test
    public void testMultiplyMM_InvalidBounds() {
        double[] smallMatrix = new double[15];
        double[] validMatrix = new double[16];

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(smallMatrix, 0, validMatrix, 0, validMatrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(validMatrix, 0, smallMatrix, 0, validMatrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(validMatrix, 0, validMatrix, 0, smallMatrix, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            DoubleMatrix.multiplyMM(validMatrix, 1, validMatrix, 0, validMatrix, 0);
        });
    }

@Test
    public void testMultiplyMM_Identity() {
        double[] identity = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        double[] input = {
            1, 2, 3, 4,
            5, 6, 7, 8,
            9, 10, 11, 12,
            13, 14, 15, 16
        };

        double[] result = new double[16];

        DoubleMatrix.multiplyMM(result, 0, input, 0, identity, 0);
        assertArrayEquals(input, result, DELTA);

        DoubleMatrix.multiplyMM(result, 0, identity, 0, input, 0);
        assertArrayEquals(input, result, DELTA);
    }

@Test
    public void testMultiplyMM_Standard() {
        // Note: Matrices in OpenGL are column-major
        double[] lhs = {
            1, 5, 9, 13,
            2, 6, 10, 14,
            3, 7, 11, 15,
            4, 8, 12, 16
        };

        double[] rhs = {
            16, 12, 8, 4,
            15, 11, 7, 3,
            14, 10, 6, 2,
            13, 9, 5, 1
        };

        double[] expected = {
            80, 240, 400, 560,
            70, 214, 358, 502,
            60, 188, 316, 444,
            50, 162, 274, 386
        };

        double[] result = new double[16];
        DoubleMatrix.multiplyMM(result, 0, lhs, 0, rhs, 0);

        assertArrayEquals(expected, result, DELTA);
    }

@Test
    public void testMultiplyMM_Overlap() {
        double[] matrix = {
            1, 5, 9, 13,
            2, 6, 10, 14,
            3, 7, 11, 15,
            4, 8, 12, 16
        };

        double[] expected = {
            90, 202, 314, 426,
            100, 228, 356, 484,
            110, 254, 398, 542,
            120, 280, 440, 600
        };

        // Pass the same array for result, lhs, and rhs.
        // This forces the "overlap" branch to be taken in multiplyMM
        DoubleMatrix.multiplyMM(matrix, 0, matrix, 0, matrix, 0);

        assertArrayEquals(expected, matrix, DELTA);
    }

@Test
    public void testLength_Zero() {
        assertEquals(0.0, DoubleMatrix.length(0, 0, 0), DELTA);
    }

@Test
    public void testLength_Positive() {
        // Pythagorean triple: 3, 4, 0 -> 5
        assertEquals(5.0, DoubleMatrix.length(3, 4, 0), DELTA);
    }

@Test
    public void testLength_Negative() {
        // Negative values should work the same since they are squared
        assertEquals(5.0, DoubleMatrix.length(-3, -4, 0), DELTA);
    }

@Test
    public void testLength_3D() {
        // 2, 3, 6 -> sqrt(4 + 9 + 36) = sqrt(49) = 7
        assertEquals(7.0, DoubleMatrix.length(2, 3, 6), DELTA);
    }

@Test
    public void testLength_HighPrecision() {
        // 1.5, 2.0, 2.5 -> sqrt(2.25 + 4.0 + 6.25) = sqrt(12.5) ≈ 3.5355339
        assertEquals(Math.sqrt(12.5), DoubleMatrix.length(1.5, 2.0, 2.5), DELTA);
    }


    @Test
    public void testMultiplyMV_Success() {
        // Identity matrix
        double[] identityMatrix = {
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        };

        double[] vector = {2.0, 3.0, 4.0, 1.0};
        double[] result = new double[4];

        DoubleMatrix.multiplyMV(result, 0, identityMatrix, 0, vector, 0);

        assertArrayEquals(new double[]{2.0, 3.0, 4.0, 1.0}, result, DELTA);

        // A non-trivial matrix
        double[] matrix = {
            1.0, 2.0, 3.0, 4.0,
            5.0, 6.0, 7.0, 8.0,
            9.0, 10.0, 11.0, 12.0,
            13.0, 14.0, 15.0, 16.0
        };
        // Result is calculated as:
        // tmp0 = 1*2 + 5*3 + 9*4 + 13*1 = 2 + 15 + 36 + 13 = 66
        // tmp1 = 2*2 + 6*3 + 10*4 + 14*1 = 4 + 18 + 40 + 14 = 76
        // tmp2 = 3*2 + 7*3 + 11*4 + 15*1 = 6 + 21 + 44 + 15 = 86
        // tmp3 = 4*2 + 8*3 + 12*4 + 16*1 = 8 + 24 + 48 + 16 = 96

        DoubleMatrix.multiplyMV(result, 0, matrix, 0, vector, 0);

        assertArrayEquals(new double[]{66.0, 76.0, 86.0, 96.0}, result, DELTA);
    }


    @Test
    public void testMultiplyMV_WithOffsets() {
        double[] matrix = new double[20];
        // Populate matrix starting at offset 2
        double[] actualMatrix = {
            1.0, 2.0, 3.0, 4.0,
            5.0, 6.0, 7.0, 8.0,
            9.0, 10.0, 11.0, 12.0,
            13.0, 14.0, 15.0, 16.0
        };
        System.arraycopy(actualMatrix, 0, matrix, 2, 16);

        double[] vector = new double[6];
        double[] actualVector = {2.0, 3.0, 4.0, 1.0};
        System.arraycopy(actualVector, 0, vector, 1, 4);

        double[] result = new double[7];

        DoubleMatrix.multiplyMV(result, 3, matrix, 2, vector, 1);

        assertEquals(66.0, result[3], DELTA);
        assertEquals(76.0, result[4], DELTA);
        assertEquals(86.0, result[5], DELTA);
        assertEquals(96.0, result[6], DELTA);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NullResultVec() {
        DoubleMatrix.multiplyMV(null, 0, new double[16], 0, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NullLhsMat() {
        DoubleMatrix.multiplyMV(new double[4], 0, null, 0, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NullRhsVec() {
        DoubleMatrix.multiplyMV(new double[4], 0, new double[16], 0, null, 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NegativeResultVecOffset() {
        DoubleMatrix.multiplyMV(new double[4], -1, new double[16], 0, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NegativeLhsMatOffset() {
        DoubleMatrix.multiplyMV(new double[4], 0, new double[16], -1, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_NegativeRhsVecOffset() {
        DoubleMatrix.multiplyMV(new double[4], 0, new double[16], 0, new double[4], -1);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_ResultVecTooSmall() {
        DoubleMatrix.multiplyMV(new double[3], 0, new double[16], 0, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_LhsMatTooSmall() {
        DoubleMatrix.multiplyMV(new double[4], 0, new double[15], 0, new double[4], 0);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testMultiplyMV_RhsVecTooSmall() {
        DoubleMatrix.multiplyMV(new double[4], 0, new double[16], 0, new double[3], 0);
    }


    @Test
    public void testScaleM_WithDestinationMatrix() {
        double[] src = {
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        };
        double[] dest = new double[16];

        DoubleMatrix.scaleM(dest, 0, src, 0, 2.0, 3.0, 4.0);

        double[] expected = {
                2.0, 0.0, 0.0, 0.0,
                0.0, 3.0, 0.0, 0.0,
                0.0, 0.0, 4.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        };

        assertArrayEquals(expected, dest, DELTA);
    }


    @Test
    public void testScaleM_WithDestinationMatrix_WithOffsets() {
        double[] src = {
                -1.0, -1.0, // padding
                1.0, 2.0, 3.0, 4.0,
                5.0, 6.0, 7.0, 8.0,
                9.0, 10.0, 11.0, 12.0,
                13.0, 14.0, 15.0, 16.0
        };
        double[] dest = new double[20];

        DoubleMatrix.scaleM(dest, 3, src, 2, 2.0, 0.5, -1.0);

        double[] expectedDestSlice = {
                2.0, 4.0, 6.0, 8.0, // x2
                2.5, 3.0, 3.5, 4.0, // x0.5
                -9.0, -10.0, -11.0, -12.0, // x-1
                13.0, 14.0, 15.0, 16.0 // same
        };

        for (int i = 0; i < 16; i++) {
            assertEquals(expectedDestSlice[i], dest[3 + i], DELTA);
        }
    }


    @Test
    public void testScaleM_InPlace() {
        double[] matrix = {
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        };

        DoubleMatrix.scaleM(matrix, 0, 5.0, 10.0, 0.0);

        double[] expected = {
                5.0, 0.0, 0.0, 0.0,
                0.0, 10.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        };

        assertArrayEquals(expected, matrix, DELTA);
    }


    @Test
    public void testScaleM_InPlace_WithOffset() {
        double[] matrix = {
                -5.0, // padding
                2.0, 4.0, 6.0, 8.0,
                10.0, 20.0, 30.0, 40.0,
                1.0, 1.0, 1.0, 1.0,
                0.0, 0.0, 0.0, 1.0
        };

        DoubleMatrix.scaleM(matrix, 1, 0.5, 0.1, 3.0);

        double[] expected = {
                -5.0, // untouched
                1.0, 2.0, 3.0, 4.0, // x0.5
                1.0, 2.0, 3.0, 4.0, // x0.1
                3.0, 3.0, 3.0, 3.0, // x3
                0.0, 0.0, 0.0, 1.0 // untouched
        };

        assertArrayEquals(expected, matrix, DELTA);
    }


    @Test
    public void testTranslateM() {
        double[] m = new double[16];
        double[] tm = new double[16];

        DoubleMatrix.setIdentityM(m, 0);
        DoubleMatrix.translateM(tm, 0, m, 0, 2.0, 3.0, 4.0);

        // Verify standard translate over identity
        assertEquals(1.0, tm[0], DELTA);
        assertEquals(0.0, tm[1], DELTA);
        assertEquals(0.0, tm[2], DELTA);
        assertEquals(0.0, tm[3], DELTA);

        assertEquals(0.0, tm[4], DELTA);
        assertEquals(1.0, tm[5], DELTA);
        assertEquals(0.0, tm[6], DELTA);
        assertEquals(0.0, tm[7], DELTA);

        assertEquals(0.0, tm[8], DELTA);
        assertEquals(0.0, tm[9], DELTA);
        assertEquals(1.0, tm[10], DELTA);
        assertEquals(0.0, tm[11], DELTA);

        assertEquals(2.0, tm[12], DELTA);
        assertEquals(3.0, tm[13], DELTA);
        assertEquals(4.0, tm[14], DELTA);
        assertEquals(1.0, tm[15], DELTA);
    }


    @Test
    public void testTranslateMWithRotation() {
        double[] m = new double[16];
        double[] tm = new double[16];

        DoubleMatrix.setIdentityM(m, 0);
        // Apply a 90 degree rotation around Z
        m[0] = 0.0; m[4] = -1.0;
        m[1] = 1.0; m[5] = 0.0;

        DoubleMatrix.translateM(tm, 0, m, 0, 2.0, 3.0, 0.0);

        // Expected translation after 90 deg rotation is (-3, 2, 0)
        assertEquals(0.0, tm[0], DELTA);
        assertEquals(1.0, tm[1], DELTA);
        assertEquals(0.0, tm[2], DELTA);
        assertEquals(0.0, tm[3], DELTA);

        assertEquals(-1.0, tm[4], DELTA);
        assertEquals(0.0, tm[5], DELTA);
        assertEquals(0.0, tm[6], DELTA);
        assertEquals(0.0, tm[7], DELTA);

        assertEquals(0.0, tm[8], DELTA);
        assertEquals(0.0, tm[9], DELTA);
        assertEquals(1.0, tm[10], DELTA);
        assertEquals(0.0, tm[11], DELTA);

        assertEquals(-3.0, tm[12], DELTA); // m[0]*2 + m[4]*3 + m[8]*0 = 0*2 - 1*3 + 0 = -3
        assertEquals(2.0, tm[13], DELTA); // m[1]*2 + m[5]*3 + m[9]*0 = 1*2 + 0*3 + 0 = 2
        assertEquals(0.0, tm[14], DELTA);
        assertEquals(1.0, tm[15], DELTA);
    }


    @Test
    public void testTranslateMInPlace() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);

        DoubleMatrix.translateM(m, 0, 1.0, 2.0, -1.0);

        assertEquals(1.0, m[12], DELTA);
        assertEquals(2.0, m[13], DELTA);
        assertEquals(-1.0, m[14], DELTA);
        assertEquals(1.0, m[15], DELTA);

        DoubleMatrix.translateM(m, 0, 2.0, 3.0, 4.0);

        assertEquals(3.0, m[12], DELTA);
        assertEquals(5.0, m[13], DELTA);
        assertEquals(3.0, m[14], DELTA);
        assertEquals(1.0, m[15], DELTA);
    }


    @Test
    public void testTranslateMInPlaceWithRotation() {
        double[] m = new double[16];
        DoubleMatrix.setIdentityM(m, 0);
        // Apply a scale (2, 3, 1) and rotation to m
        m[0] = 0.0; m[4] = -3.0; // scale y * sin(90)
        m[1] = 2.0; m[5] = 0.0;  // scale x * cos(90)

        DoubleMatrix.translateM(m, 0, 2.0, 3.0, 0.0);

        assertEquals(-9.0, m[12], DELTA); // 0*2 + -3*3 + 0*0
        assertEquals(4.0, m[13], DELTA);  // 2*2 + 0*3 + 0*0
        assertEquals(0.0, m[14], DELTA);
        assertEquals(1.0, m[15], DELTA);
    }


    @Test
    public void testTranslateMWithOffsets() {
        double[] m = new double[20]; // offset by 4
        double[] tm = new double[20]; // offset by 4

        DoubleMatrix.setIdentityM(m, 4);
        DoubleMatrix.translateM(tm, 4, m, 4, 1.5, 2.5, -3.5);

        // Check translation
        assertEquals(1.5, tm[16], DELTA); // 12 + 4
        assertEquals(2.5, tm[17], DELTA); // 13 + 4
        assertEquals(-3.5, tm[18], DELTA); // 14 + 4
        assertEquals(1.0, tm[19], DELTA); // 15 + 4

        // Ensure other values are set correctly
        assertEquals(1.0, tm[4], DELTA);
        assertEquals(1.0, tm[9], DELTA);
        assertEquals(1.0, tm[14], DELTA);

        // Ensure data before offset is left untouched
        assertEquals(0.0, tm[0], DELTA);
        assertEquals(0.0, tm[3], DELTA);
    }
}
