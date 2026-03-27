"""iOS evaluation backend.

Calls the host-side simulator daemon over HTTP to interact with the iOS
Simulator. Extends the shared BookSnapBackend with the iOS-specific
build action name.
"""

from booksnap_labs.backend_base import BookSnapBackend


class IOSBackend(BookSnapBackend):
    build_action = "build"
