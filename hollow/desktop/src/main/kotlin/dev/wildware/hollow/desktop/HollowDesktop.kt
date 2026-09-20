package dev.wildware.hollow.desktop

/**
 * `sh gradlew :hollow:desktop:run`: a listen server and its local player - the authoritative server
 * on UDP port 27025 in this process, and a window that joins it (issue #249). Another machine joins
 * with `runClient --args="join <this machine>"`.
 *
 * `-Plevel=<path>` plays another `.udealevel` instead of the bundled clearing.
 */
public object HollowDesktop {

    @JvmStatic
    public fun main(args: Array<String>) {
        HollowClientMain.host()
    }
}
