# Release-build R8 rules for RideLink.
#
# Compose, AndroidX lifecycle/ViewModel and play-services-nearby ship their own
# consumer rules, and Concentus (pure-Java Opus) uses no reflection, so nothing
# extra is needed today. Manifest components (activities, services) are kept
# automatically. If a future dependency needs a keep rule, add it here.
