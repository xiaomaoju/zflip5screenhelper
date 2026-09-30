package io.github.flipcover.controls;
import android.os.Bundle;
import io.github.flipcover.controls.IConnectivityListener;
interface IShellService {
    String execute(String operation, int displayId, int value, String component) = 1;
    Bundle taskSnapshot(int displayId, String task) = 2;
    void watchConnectivity(IConnectivityListener listener) = 3;
    void destroy() = 16777114;
}
