<?php
// Phel's view of cohost (bb boot:phel): its sources are src/ alone. target/
// holds the ClojureScript build's copies of them, which Phel would also load.
use Phel\Config\PhelConfig;
use Phel\Config\ProjectLayout;

return PhelConfig::forProject(ProjectLayout::Flat)
    ->withSrcDirs(['src'])
    ->withTestDirs([]);
