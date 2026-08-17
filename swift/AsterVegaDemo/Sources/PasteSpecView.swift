import AsterVega
import SwiftUI

/// Paste a Vega specification and see it drawn, with whatever controls it asks for.
///
/// This is the screen that can fail, and the one worth having for it. Everything else in the app shows
/// specifications chosen because they work; this one takes whatever arrives and reports what the engine
/// made of it — the chart, the controls, and every part it could not honour, named.
struct PasteSpecView: View {
  @State private var text: String = Self.startingSpecification
  @State private var session = ChartSession()
  @State private var editing = true

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        if editing {
          editor
        } else {
          chart
        }
      }
      .padding()
    }
    .navigationTitle("Paste a specification")
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) {
        Button(editing ? "Render" : "Edit") {
          if editing { session.load(specification: text) }
          editing.toggle()
        }
        .bold()
      }
    }
    .task {
      // `-spec <path>` renders a specification from a file, which is what makes this screen scriptable:
      // the simulator cannot tap a button, and reading the clipboard raises a system permission alert
      // that cannot be dismissed from the command line either. Write the file into the app's container
      // with `simctl get_app_container` and pass the path.
      if let path = UserDefaults.standard.string(forKey: "spec"),
        let contents = try? String(contentsOfFile: path, encoding: .utf8),
        !contents.isEmpty
      {
        text = contents
        editing = false
      }
      // Rendered once on arrival, so the screen shows a chart with a working control rather than an
      // empty frame and an instruction.
      session.load(specification: text)
    }
  }

  private var editor: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack {
        Button {
          if let pasted = UIPasteboard.general.string { text = pasted }
        } label: {
          Label("Paste", systemImage: "doc.on.clipboard")
        }
        Spacer()
        Button("Reset") { text = Self.startingSpecification }
          .foregroundStyle(.secondary)
      }
      .font(.callout)

      TextEditor(text: $text)
        .font(.system(.footnote, design: .monospaced))
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .frame(minHeight: 280)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.3)))

      Text("Data must be inline: this app has no network loader, so a `url` becomes a diagnostic.")
        .font(.caption)
        .foregroundStyle(.secondary)
    }
  }

  @ViewBuilder
  private var chart: some View {
    if session.loading && session.scene == nil {
      // Said out loud because a dataset Vega serves rather than the bundle takes a moment, and a blank
      // frame is indistinguishable from a chart that drew nothing.
      HStack(spacing: 8) {
        ProgressView()
        Text("Loading data…").foregroundStyle(Color.secondary)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }

    if let scene = session.scene {
      SceneCanvas(scene: scene, session: session)
        .frame(maxWidth: .infinity)
        .background(Color(white: 0.97))
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.secondary.opacity(0.3)))
    } else if let failure = session.failure {
      Text(failure)
        .font(.callout)
        .foregroundStyle(.red)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    if let scene = session.scene {
      ExportButton(scene: scene, name: "pasted-chart")
    }

    if session.canReset {
      Button("Reset view") { session.resetViewport() }
        .font(.caption)
    }

    if let touched = session.lastTouch {
      Text(touched).font(.caption).foregroundStyle(Color.accentColor)
    }

    if !session.controls.isEmpty {
      ChartControls(controls: session.controls, session: session)
    }

    if !session.diagnostics.isEmpty {
      VStack(alignment: .leading, spacing: 8) {
        Text("Not honoured").font(.headline)
        ForEach(Array(session.diagnostics.enumerated()), id: \.offset) { _, diagnostic in
          VStack(alignment: .leading, spacing: 2) {
            Text(diagnostic.message).font(.callout)
            Text(describe(diagnostic)).font(.caption2).foregroundStyle(.secondary)
          }
          .frame(maxWidth: .infinity, alignment: .leading)
        }
      }
    }
  }

  private func describe(_ diagnostic: VegaDiagnostic) -> String {
    let severity: String
    switch diagnostic.severity {
    case DiagnosticSeverity.fatal: severity = "fatal"
    case DiagnosticSeverity.error: severity = "error"
    case DiagnosticSeverity.warning: severity = "warning"
    default: severity = "info"
    }
    guard let path = diagnostic.jsonPath, !path.isEmpty else { return severity }
    return "\(severity) · \(path)"
  }

  /// What the screen starts with: a chart whose bars a slider actually moves.
  ///
  /// Chosen to have a `bind` on purpose. A specification with no controls would demonstrate the paste
  /// box and leave the more interesting half of this screen invisible.
  private static let startingSpecification = """
    {
      "$schema": "https://vega.github.io/schema/vega/v6.json",
      "width": 320, "height": 200, "padding": 5,
      "background": "white",
      "signals": [
        {
          "name": "scale", "value": 1,
          "bind": {"input": "range", "min": 0.2, "max": 2, "step": 0.1}
        },
        {
          "name": "colour", "value": "steelblue",
          "bind": {"input": "select", "options": ["steelblue", "seagreen", "firebrick", "goldenrod"]}
        },
        {"name": "outlined", "value": false, "bind": {"input": "checkbox"}}
      ],
      "data": [{
        "name": "table",
        "values": [
          {"c": "A", "v": 28}, {"c": "B", "v": 55}, {"c": "C", "v": 43},
          {"c": "D", "v": 91}, {"c": "E", "v": 81}, {"c": "F", "v": 53}
        ]
      }],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "table", "field": "c"},
         "range": "width", "padding": 0.1},
        {"name": "y", "type": "linear", "domain": [0, 100], "range": "height", "nice": true}
      ],
      "axes": [
        {"orient": "bottom", "scale": "x"},
        {"orient": "left", "scale": "y"}
      ],
      "marks": [{
        "type": "rect",
        "from": {"data": "table"},
        "encode": {
          "update": {
            "x": {"scale": "x", "field": "c"},
            "width": {"scale": "x", "band": 1},
            "y": {"scale": "y", "signal": "datum.v * scale"},
            "y2": {"scale": "y", "value": 0},
            "fill": {"signal": "colour"},
            "stroke": {"signal": "outlined ? 'black' : null"},
            "strokeWidth": {"value": 1}
          }
        }
      }]
    }
    """
}
