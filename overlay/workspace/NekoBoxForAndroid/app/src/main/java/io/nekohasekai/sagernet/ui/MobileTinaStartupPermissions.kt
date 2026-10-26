package io.nekohasekai.sagernet.ui

/** One sequential permission pass per Activity visit; denial never blocks the next step. */
internal class MobileTinaStartupPermissions(stage: Int = 0, waiting: Boolean = false) {
    enum class Request { Camera, Notifications, Vpn }

    var stage: Int = stage.coerceIn(0, Request.entries.size)
        private set
    var waiting: Boolean = waiting && this.stage < Request.entries.size
        private set

    fun next(sdk: Int, cameraGranted: Boolean, notificationsGranted: Boolean): Request? {
        if (waiting) return null
        while (stage < Request.entries.size) {
            val request = Request.entries[stage]
            val skip = when (request) {
                Request.Camera -> cameraGranted
                Request.Notifications -> sdk < 33 || notificationsGranted
                Request.Vpn -> false
            }
            if (skip) {
                stage++
            } else {
                waiting = true
                return request
            }
        }
        return null
    }

    fun complete(request: Request) {
        if (waiting && stage == request.ordinal) {
            waiting = false
            stage++
        }
    }
}
