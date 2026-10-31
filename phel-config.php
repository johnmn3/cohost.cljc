<?php
// Phel's view of cohost (bb boot:phel): its sources are src/ alone. target/
// holds the ClojureScript build's copies of them, which a scan would also
// load: a Phel scan skips the build output directory, so that is target/
// (cohost never runs phel build).
use Phel\Config\PhelConfig;
use Phel\Config\ProjectLayout;

return PhelConfig::forProject(ProjectLayout::Flat)
    ->withSrcDirs(['src'])
    ->withTestDirs([])
    ->withBuildDestDir('target');
