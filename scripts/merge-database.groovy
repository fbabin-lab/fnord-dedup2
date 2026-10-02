#!/usr/bin/env groovy
// Run with bin/fnord-dedup2-groovy from the installed distribution.
// Redirect stdout to save the streaming JSON report; diagnostics go to stderr.
import fnord.dedup.merge.MergeCommand
System.exit(MergeCommand.run(args))
