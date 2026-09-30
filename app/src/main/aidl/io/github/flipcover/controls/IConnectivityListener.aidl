package io.github.flipcover.controls;
/** Invalidation only: credentials and device identities never travel in events. */
oneway interface IConnectivityListener {
    void onChanged();
}
