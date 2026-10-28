plugins {
    id("netherforge.paper-adapter")
}

paperAdapter {
    // Paper published 1.21.x's API and dev bundle as snapshots; 1.21.11's last one is from server build 132.
    paper = "1.21.11-R0.1-SNAPSHOT"
    serverBuild = 132
}
