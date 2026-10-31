<?php
// Phel's view of cohost (bb boot:phel): its sources are src/ alone. target/
// holds the ClojureScript build's copies of them, which a scan would also
// load: a Phel scan skips the build output directory, so that is target/
// (cohost never runs phel build).
// A float becomes a string, in the code Phel compiles too, with PHP's
// precision (14 digits by default: 0.49999999999999994 would compile as 0.5);
// -1 is the shortest string that reads back as the same double, as the JVM
// writes it. Set here, before Phel compiles anything.
ini_set('precision', '-1');

use Phel\Config\PhelConfig;
use Phel\Config\ProjectLayout;

return PhelConfig::forProject(ProjectLayout::Flat)
    ->withSrcDirs(['src'])
    ->withTestDirs([])
    ->withBuildDestDir('target');
