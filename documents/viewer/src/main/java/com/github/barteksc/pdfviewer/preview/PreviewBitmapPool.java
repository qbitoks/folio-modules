// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer.preview;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class PreviewBitmapPool<T> {

    private final PreviewBitmapAdapter<T> adapter;
    private final int perBucketCap;
    private final Map<Integer, ArrayDeque<T>> buckets = new HashMap<>();

    PreviewBitmapPool(PreviewBitmapAdapter<T> adapter, int perBucketCap) {
        this.adapter = adapter;
        this.perBucketCap = perBucketCap;
    }

    public synchronized T acquire(int bucketWidthPx) {
        ArrayDeque<T> free = buckets.get(bucketWidthPx);
        return free == null ? null : free.pollFirst();
    }

    public synchronized void release(int bucketWidthPx, T bitmap) {
        if (bitmap == null) {
            return;
        }
        ArrayDeque<T> free = buckets.get(bucketWidthPx);
        if (free == null) {
            free = new ArrayDeque<>();
            buckets.put(bucketWidthPx, free);
        }
        if (free.size() < perBucketCap) {
            free.addFirst(bitmap);
        } else {
            adapter.recycle(bitmap);
        }
    }

    public synchronized void clear() {
        for (ArrayDeque<T> free : buckets.values()) {
            for (T bitmap : free) {
                adapter.recycle(bitmap);
            }
        }
        buckets.clear();
    }

    synchronized int size(int bucketWidthPx) {
        ArrayDeque<T> free = buckets.get(bucketWidthPx);
        return free == null ? 0 : free.size();
    }
}
