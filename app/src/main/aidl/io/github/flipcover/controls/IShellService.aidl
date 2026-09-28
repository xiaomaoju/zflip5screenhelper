package io.github.flipcover.controls;
import android.os.Bundle;
interface IShellService {
    String execute(String operation, int displayId, int value, String component) = 1;
    Bundle taskSnapshot(int displayId, String task) = 2;
    void destroy() = 16777114;
}
