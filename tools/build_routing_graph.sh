#!/usr/bin/env bash
# Build a GraphHopper routing graph for the app from an OpenStreetMap extract.
#
# Why this exists: a phone cannot build a routing graph itself. GraphHopper's OSM reader depends on
# javax.xml.stream, which Android does not implement, so importing a .osm.pbf on a device fails while
# loading the reader class. The app therefore ships/loads a graph that was built here, on a JVM.
#
# Usage:
#   ./build_routing_graph.sh region.osm.pbf out-graph-dir
#   (cd out-graph-dir && zip -qr ../routing_region.zip .)
#
# Requirements: JDK 17 and internet access for the two GraphHopper artifacts (or a populated cache).
set -euo pipefail

OSM=${1:-}
OUT=${2:-}
if [ -z "$OSM" ] || [ -z "$OUT" ]; then
  echo "usage: $0 <region.osm.pbf> <out-graph-dir>" >&2
  exit 2
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/settings.gradle.kts" <<'EOF'
rootProject.name = "graph-builder"
EOF

cat > "$WORK/build.gradle.kts" <<'EOF'
plugins { java; application }
repositories { mavenCentral() }
dependencies {
    implementation("com.graphhopper:graphhopper-core:2.4")
    implementation("com.graphhopper:graphhopper-reader-osm:2.4")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
application { mainClass.set("BuildGraph") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
EOF

mkdir -p "$WORK/src/main/java"
cat > "$WORK/src/main/java/BuildGraph.java" <<'EOF'
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.osm.GraphHopperOSM;
import java.io.File;
import java.util.List;

public class BuildGraph {
    public static void main(String[] args) {
        GraphHopperConfig config = new GraphHopperConfig()
            .putObject("datareader.file", new File(args[0]).getAbsolutePath())
            .putObject("graph.location", new File(args[1]).getAbsolutePath())
            // Must match RoutingEngine.ENCODING_FLAGS in the app.
            .putObject("graph.flag_encoders", "car,bike,foot")
            .setProfiles(List.of(
                new Profile("car").setVehicle("car").setWeighting("fastest"),
                new Profile("bike").setVehicle("bike").setWeighting("fastest"),
                new Profile("foot").setVehicle("foot").setWeighting("shortest")));
        GraphHopperOSM hopper = new GraphHopperOSM();
        hopper.init(config);
        hopper.setStoreOnFlush(true);
        hopper.setAllowWrites(true);
        long started = System.currentTimeMillis();
        hopper.importOrLoad();
        System.out.println("graph built in " + (System.currentTimeMillis() - started) + " ms -> " + args[1]);
        hopper.close();
    }
}
EOF

cp "$OSM" "$WORK/region.osm.pbf"
mkdir -p "$OUT"
( cd "$WORK" && gradle --quiet run --args="region.osm.pbf $OUT" 2>/dev/null \
  || gradle run --args="region.osm.pbf $OUT" )

echo
echo "Graph written to $OUT"
echo "Package it for the app with:  (cd $OUT && zip -qr routing_region.zip .)"
echo "Then drop routing_region.zip into app/src/main/assets/ (or host it as a catalogue package)."
