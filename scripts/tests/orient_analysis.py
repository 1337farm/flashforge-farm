#!/usr/bin/env python3
"""Quick orientation-pick analyzer for the farm auto-orienter.

Pure-stdlib; no numpy (the CI/termux host has none). Answers the design
questions that drive farm_orient.cpp WITHOUT a device or NDK build:

  1. What flat face does the (support-area) orienter pick for a given STL?
  2. Is that face the "proper base" a human would choose (largest flat,
     most stable)?
  3. Is the choice pose-independent (the rotated model behaves identically)?

It REPLICATES the upstream decision faithfully at the level that matters:
the candidate bottoms are the dominant face-normal clusters of the mesh
(plus the flat-hull facets), and the winning bottom is the cluster that
maximizes contact + low-angle support (path approximation of the height /
stability / overhang scoring).

Usage:
    python3 scripts/tests/orient_analysis.py model.stl \
        [--rotate "x 45 y -20 z 90" | --eps 0.01] [--overhang 30]

Outputs a per-model report. Exit 0 always (it is a diagnostic, not a gate).
"""

import math
import struct
import sys
from collections import defaultdict

SQRT3 = math.sqrt(3.0)


def parse_stl(path):
    """Return (verts-as-triangles list of 3-tuples)."""
    with open(path, "rb") as f:
        head = f.read(5)
        if head == b"solid":
            raise SystemExit("ASCII STL not supported; re-export as binary")
    f = open(path, "rb")
    f.seek(80)
    n = struct.unpack("<I", f.read(4))[0]
    tris = []
    for _ in range(n):
        f.read(12)  # normals
        a = struct.unpack("<3f", f.read(12))
        b = struct.unpack("<3f", f.read(12))
        c = struct.unpack("<3f", f.read(12))
        f.read(2)
        tris.append((a, b, c))
    f.close()
    if len(tris) != n:
        raise SystemExit(f"STL truncated: {len(tris)} != {n} triangles")
    return tris


def tri_stats(a, b, c):
    ab = (b[0]-a[0], b[1]-a[1], b[2]-a[2])
    ac = (c[0]-a[0], c[1]-a[1], c[2]-a[2])
    n = (ab[1]*ac[2]-ab[2]*ac[1],
         ab[2]*ac[0]-ab[0]*ac[2],
         ab[0]*ac[1]-ab[1]*ac[0])
    nrm = (n[0]*n[0] + n[1]*n[1] + n[2]*n[2]) ** 0.5
    if nrm < 1e-12:
        return None
    n = (n[0]/nrm, n[1]/nrm, n[2]/nrm)
    area = 0.5 * nrm
    cx = (a[0]+b[0]+c[0])/3.0
    cy = (a[1]+b[1]+c[1])/3.0
    cz = (a[2]+b[2]+c[2])/3.0
    return n, area, (cx, cy, cz)


def bucketed(n, eps):
    """Quantize a unit normal to a cluster bucket using eps (deg)."""
    # Reuse the upstream tolerance idea: bucket by rounded direction.
    k = 100.0 / max(eps, 0.5)
    return (round(n[0]*k), round(n[1]*k), round(n[2]*k))


def rotate_vec(v, rx, ry, rz):
    x, y, z = v
    # ZXY app order approximating upstream (cosmetic here, any rotation works)
    cx, sx = math.cos(ry), math.sin(ry)
    y, z = y*cx - z*sx, y*sx + z*cx
    cx, sx = math.cos(rx), math.sin(rx)
    z, x = z*cx - x*sx, z*sx + x*cx
    cx, sx = math.cos(rz), math.sin(rz)
    x, y = x*cx - y*sx, x*sx + y*cx
    return (x, y, z)


def dot(a, b):
    return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def analyze(tris, eps, overhang, rx=0.0, ry=0.0, rz=0.0, label=""):
    clusters = defaultdict(float)
    norm_avg = {}
    nr = (rx, ry, rz)
    rot = lambda v: rotate_vec(v, *nr)
    stats = []
    for (a, b, c) in tris:
        aa, bb, cc = (rot(a), rot(b), rot(c))
        s = tri_stats(aa, bb, cc)
        if s is None:
            continue
        n, area, cen = s
        key = bucketed(n, eps)
        clusters[key] += area
        prev = norm_avg.get(key)
        if prev is None:
            norm_avg[key] = list(n)
        else:
            for i in range(3):
                prev[i] += n[i]
        stats.append((n, area, cen))
    for k, acc in norm_avg.items():
        nrm = math.sqrt(sum(v*v for v in acc))
        norm_avg[k] = [v/nrm for v in acc]

    total = sum(clusters.values())
    ranked = sorted(clusters.items(), key=lambda kv: -kv[1])

    # Flatness/goodness of each cluster as a base: recompute exact cluster
    # normal from accumulated, area-weighted.
    def goodness(key, area):
        n = norm_avg[key]
        if n[2] > -math.cos(math.radians(overhang)) - 1e-9:
            return None  # not a candidate for resting on this face
        # contact area for this bottom: faces exactly parallel dominate
        contact = sum(a for (nn, a, _) in stats if abs(dot(nn, n)) > 0.9999)
        ratio = area / total
        return contact, ratio

    report = []
    for i, (key, area) in enumerate(ranked[:8]):
        n = norm_avg[key]
        g = goodness(key, area)
        if g is None:
            report.append((n, area, area/total*100.0, None, None))
            continue
        contact, ratio = g
        report.append((n, area, area/total*100.0, contact, contact/total*100.0))

    native_base = None
    big_flat = ranked[0]
    bn = norm_avg[big_flat[0]]
    if bn[2] < -0.95:
        native_base = big_flat

    height = 0.0
    bmin = [1e9, 1e9, 1e9]
    bmax = [-1e9, -1e9, -1e9]
    for (a, b, c) in tris:
        aa, bb, cc = (rot(a), rot(b), rot(c))
        z = max(aa[2], bb[2], cc[2])
        if z > height:
            height = z
        for p in (aa, bb, cc):
            for k in range(3):
                if p[k] < bmin[k]:
                    bmin[k] = p[k]
                if p[k] > bmax[k]:
                    bmax[k] = p[k]

    bbox = (bmax[0]-bmin[0], bmax[1]-bmin[1], bmax[2]-bmin[2])

    print("=" * 72)
    if label:
        print(f"MODEL: {path}  |  {label}")
    else:
        print(f"MODEL: {path}")
    print(f"  triangles={len(tris)}  total_area={total:.1f}  bbox(WxHxD)={bbox[0]:.2f}x{bbox[2]:.2f}x{bbox[1]:.2f}")
    print(f"  top-8 flat-face clusters (cluster normal, area, %total, contact, %):")
    for r in report[:8]:
        n, area, rt, contact, cr = r
        if contact is None:
            print(f"    n=({n[0]:+.3f},{n[1]:+.3f},{n[2]:+.3f})  area={area:9.1f} {rt:5.1f}%   (not a flat-down base)")
        else:
            print(f"    n=({n[0]:+.3f},{n[1]:+.3f},{n[2]:+.3f})  area={area:9.1f} {rt:5.1f}%   contact={contact:9.1f} {cr:5.1f}%")
    if native_base:
        print(f"  native frame ALREADY has a flat base (cluster Z={bn[2]:+.3f}, {big_flat[1]/total*100:.1f}% of area)")
    else:
        print("  native frame has NO clearly-flat base (top cluster Z=%.3f)" % bn[2])

    low = math.sin(math.radians(overhang))  # faces pointed steeper than this (from horizontal) need support
    cand = []
    for key, area in ranked[:20]:
        n = norm_avg[key]
        # Resting orientation: Rodrigues rotation mapping the candidate base
        # normal n onto -Z (the bed), then score contact/support/height.
        a_ = cross(n, (0.0, 0.0, -1.0))
        na = math.sqrt(a_[0]*a_[0]+a_[1]*a_[1]+a_[2]*a_[2])
        if na < 1e-9:
            continue  # already horizontal; not a candidate in native analysis
        ax = a_[0] / na; ay = a_[1] / na; az = a_[2] / na
        cos_a = dot(n, (0.0, 0.0, -1.0))
        sin_a = na

        def rod(v):
            x, y, z = v
            v_dot = x*ax + y*ay + z*az
            crossv = (ay*z - az*y, az*x - ax*z, ax*y - ay*x)
            return (x*cos_a + v_dot*ax*(1-cos_a) + crossv[0]*sin_a,
                    y*cos_a + v_dot*ay*(1-cos_a) + crossv[1]*sin_a,
                    z*cos_a + v_dot*az*(1-cos_a) + crossv[2]*sin_a)

        contact_area = support_area = 0.0
        for (nn, aa, _) in stats:
            w = rod(nn)
            if w[2] < -0.9999:
                contact_area += aa
            elif w[2] < -low:
                support_area += aa

        if contact_area <= 0.0:
            continue  # not a flat-down resting base
        # compute rotated bbox via a quick pass over triangles
        zmax = -1e9; zmin = 1e9; ex = ey = 0.0
        for (a, b, c) in tris:
            for p in (a, b, c):
                rp = rod(rot(p))
                if rp[2] > zmax: zmax = rp[2]
                if rp[2] < zmin: zmin = rp[2]
                if rp[0] > ex: ex = rp[0]
                if rp[1] > ey: ey = rp[1]
        ht = zmax - zmin
        fd = math.sqrt(ex*ex + ey*ey)
        score = (support_area / total) + 0.30 * (ht / max(fd, 1e-6))
        cand.append((score, area, contact_area, support_area, ht, fd, n, key))

    cand.sort(key=lambda c: c[0])
    if cand:
        print("  -- decision mimic (support + height stability): --")
        for (score, area, ca, sp, ht, fd, n, key) in cand[:3]:
            print(f"    bottom normal n=({n[0]:+.3f},{n[1]:+.3f},{n[2]:+.3f})  contact={ca:7.1f} support={sp:7.1f} "
                  f"height={ht:5.2f} footprint={fd:5.2f}  score={score:.4f}")
    return ranked, norm_avg, stats, total


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    path = args[0]
    eps = 1.0
    overhang = 30.0
    rx = ry = rz = 0.0
    rot_desc = ""
    i = 1
    while i < len(args):
        a = args[i]
        if a == "--rotate":
            j = i + 1
            while j < len(args) and not args[j].startswith("--"):
                if args[j].startswith("x"):
                    rx = float(args[j][1:])
                if args[j].startswith("y"):
                    ry = float(args[j][1:])
                if args[j].startswith("z"):
                    rz = float(args[j][1:])
                j += 1
            rot_desc = f"applied rotation rx={rx} ry={ry} rz={rz} deg"
            i = j
        elif a == "--eps":
            eps = float(args[i+1])
            i += 2
        elif a == "--overhang":
            overhang = float(args[i+1])
            i += 2
        else:
            i += 1

    tris = parse_stl(path)
    # Native-frame analysis
    ranked, norm_avg, stats, total = analyze(tris, eps, overhang, label=rot_desc or "native frame")
    # Pose-independence check: re-run under an arbitrary rotation; the chosen
    # top face must be the same WORLD plane (its world normal unchanged).
    if not rot_desc:
        import random
        rnd = random.Random(42)
        rx2, ry2, rz2 = rnd.uniform(0, 180), rnd.uniform(0, 180), rnd.uniform(0, 180)
        print(f"  [invariance probe] re-runs under random rotation rx={rx2:.0f} ry={ry2:.0f} rz={rz2:.0f}:")
        r2, na2, _, _ = analyze(tris, eps, overhang, rx2, ry2, rz2, label=f"random rotation {rx2:.0f}/{ry2:.0f}/{rz2:.0f}")
        w_n_native = norm_avg[ranked[0][0]]
        w_n_rnd = rotate_vec(na2[r2[0][0]], math.radians(rx2), math.radians(ry2), math.radians(rz2))
        q = dot(w_n_native, w_n_rnd)
        print(f"    native top-face normal vs rotated-run top-face (world) dot={q:+.3f}"
              f"  -> {'POSE-INDEPENDENT' if q > 0.99 else 'pose-DEPENDENT'}")