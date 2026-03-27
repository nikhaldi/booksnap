"""Android evaluation backend.

Calls the host-side emulator daemon over HTTP to interact with the Android
emulator. Extends the shared BookSnapBackend with the Android-specific
build action name.
"""

from booksnap_labs.backend_base import BookSnapBackend


class AndroidBackend(BookSnapBackend):
    build_action = "build_apks"
