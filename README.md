# gps_forwarder
A helper app for Android. It forwards GPS data for life tracking.

It  uses the protocol defined by mapmytracks.com.

This app does not determine locations by itself. But whenever any other app gets a GPX fix, GPS Forwarder will be notified, and it will forward the position to a lifetracking server.

Still missing:
- installable from F-Droid
- internationalization

ABOUT PRIVACY
=============

This app only sends data as defined by https://github.com/MapMyTracks/api. It only sends the data
to the server configured by you in the settings.
