#!/bin/bash
#
# Renders Mermaid diagrams from README.md to SVG/PNG files
# Requires: npm install -g @mermaid-js/mermaid-cli
#
# Usage: ./scripts/render-diagrams.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
README="$PROJECT_DIR/README.md"
OUTPUT_DIR="$PROJECT_DIR/docs/diagrams"

# Check for mermaid-cli
if ! command -v mmdc &> /dev/null; then
    echo "Error: mermaid-cli (mmdc) not found"
    echo "Install with: npm install -g @mermaid-js/mermaid-cli"
    exit 1
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "Extracting Mermaid diagrams from README.md..."

# Extract diagrams with context-aware naming
# Finds the preceding ### heading to name each diagram
awk '
BEGIN { diagram_num = 0; heading = "diagram" }
/^### / {
    # Extract heading text, convert to lowercase kebab-case
    heading = $0
    gsub(/^### /, "", heading)
    gsub(/[^a-zA-Z0-9 ]/, "", heading)
    gsub(/ +/, "-", heading)
    heading = tolower(heading)
}
/^```mermaid$/ {
    capture = 1
    diagram_num++
    filename = "'"$OUTPUT_DIR"'/" heading ".mmd"
    next
}
/^```$/ && capture {
    capture = 0
    next
}
capture {
    print >> filename
}
' "$README"

# Count diagrams
DIAGRAM_COUNT=$(ls -1 "$OUTPUT_DIR"/*.mmd 2>/dev/null | wc -l | tr -d ' ')

if [ "$DIAGRAM_COUNT" -eq 0 ]; then
    echo "No Mermaid diagrams found in README.md"
    exit 0
fi

echo "Found $DIAGRAM_COUNT diagram(s)"

# Mermaid config for beautiful rendering
cat > "$OUTPUT_DIR/mermaid-config.json" << 'EOF'
{
  "theme": "base",
  "themeVariables": {
    "primaryColor": "#e3f2fd",
    "primaryTextColor": "#1a237e",
    "primaryBorderColor": "#1976d2",
    "lineColor": "#455a64",
    "secondaryColor": "#fff3e0",
    "tertiaryColor": "#e8f5e9",
    "background": "#ffffff",
    "mainBkg": "#e3f2fd",
    "nodeBorder": "#1976d2",
    "clusterBkg": "#fafafa",
    "clusterBorder": "#e0e0e0",
    "titleColor": "#1a237e",
    "edgeLabelBackground": "#ffffff",
    "fontFamily": "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
    "fontSize": "14px",
    "labelTextColor": "#37474f"
  },
  "flowchart": {
    "htmlLabels": true,
    "curve": "basis",
    "padding": 15,
    "nodeSpacing": 50,
    "rankSpacing": 50,
    "diagramPadding": 20
  }
}
EOF

# Render each diagram
for mmd_file in "$OUTPUT_DIR"/*.mmd; do
    base_name=$(basename "$mmd_file" .mmd)
    svg_file="$OUTPUT_DIR/$base_name.svg"
    png_file="$OUTPUT_DIR/$base_name.png"

    echo "Rendering $base_name..."

    # Render to SVG (vector, smaller file size, best for web)
    mmdc -i "$mmd_file" -o "$svg_file" -c "$OUTPUT_DIR/mermaid-config.json" -b transparent 2>/dev/null || {
        echo "  Warning: SVG rendering failed, trying with white background..."
        mmdc -i "$mmd_file" -o "$svg_file" -c "$OUTPUT_DIR/mermaid-config.json" -b white
    }

    # Render to PNG (raster, broader compatibility)
    mmdc -i "$mmd_file" -o "$png_file" -c "$OUTPUT_DIR/mermaid-config.json" -b white -w 1400 -H 800 2>/dev/null || {
        echo "  Warning: PNG rendering with size failed, using defaults..."
        mmdc -i "$mmd_file" -o "$png_file" -c "$OUTPUT_DIR/mermaid-config.json" -b white
    }

    echo "  Created: $svg_file"
    echo "  Created: $png_file"
done

# Clean up temp files
rm -f "$OUTPUT_DIR"/*.mmd "$OUTPUT_DIR"/mermaid-config.json

echo ""
echo "Done! Diagrams rendered to $OUTPUT_DIR/"
echo ""
echo "Generated files:"
ls -1 "$OUTPUT_DIR"/*.{svg,png} 2>/dev/null | while read f; do
    size=$(du -h "$f" | cut -f1)
    echo "  $size  $(basename "$f")"
done
