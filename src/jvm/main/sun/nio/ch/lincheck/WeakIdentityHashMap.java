/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package sun.nio.ch.lincheck;

import java.lang.ref.*;
import java.util.*;
/**
 * A hash map with weak keys, based on identity comparison (==) rather than equality comparison (.equals).
 * When the key of a particular entry is no longer in ordinary use, that entry may be discarded.
 */
public class WeakIdentityHashMap<K, V> {

    // The map storing weak references of the keys and their corresponding values
    private final HashMap<WeakReference<K>, V> mMap = new HashMap<>();

    // ReferenceQueue used for cleaning up the expired entries
    private final ReferenceQueue<Object> mRefQueue = new ReferenceQueue<>();

    /**
     * Helper method for cleaning up the map from entries with expired keys.
     */
    private void cleanUp() {
        Reference<?> ref;
        while ((ref = mRefQueue.poll()) != null) {
            mMap.remove(ref);
        }
    }

    /**
     * Adds or replaces the value for the given key.
     *
     * @param key - The key associated with the value.
     * @param value - The value to be stored.
     */
    public void put(K key, V value) {
        cleanUp();
        mMap.put(new Ref<>(key, mRefQueue), value);
    }

    /**
     * Fetches the value for the given key.
     *
     * @param key - The key for which the value is needed.
     * @return The value associated with the key, or null if not found.
     */
    public V get(K key) {
        cleanUp();
        return mMap.get(new Ref<>(key));
    }

    /**
     * @return Collection of all the values in the map.
     */
    public Collection<V> values() {
        cleanUp();
        return mMap.values();
    }

    /**
     * @return Set of all entries in the map.
     */
    public Set<Map.Entry<WeakReference<K>, V>> entrySet() {
        return mMap.entrySet();
    }

    /**
     * @return The size of the map.
     */
    public int size() {
        cleanUp();
        return mMap.size();
    }

    /**
     * @return True if the map is empty, otherwise false.
     */
    public boolean isEmpty() {
        cleanUp();
        return mMap.isEmpty();
    }

    /**
     * Helper class to handle weak reference keys with correct equals and hashCode behavior.
     */
    private static class Ref<K> extends WeakReference<K> {

        // Cached hash code to ensure consistency after the referent has been cleared
        private final int mHashCode;

        public Ref(K key) {
            super(key);
            mHashCode = System.identityHashCode(key);
        }

        public Ref(K key, ReferenceQueue<Object> refQueue) {
            super(key, refQueue);
            mHashCode = System.identityHashCode(key);
        }

        /**
         * @return True if the given object is identical to this reference, otherwise false.
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            K k = get();
            if (k != null && o instanceof WeakIdentityHashMap.Ref) {
                return ((Ref) o).get() == k;
            }
            return false;
        }

        /**
         * @return The hash code of this reference.
         */
        @Override
        public int hashCode() {
            return mHashCode;
        }
    }
}
