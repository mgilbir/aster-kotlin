import AsterVega
import SwiftUI

/// The controls a specification asked for, drawn as native iOS ones.
///
/// A `bind` in a specification is a *description* of a control, not an implementation of one — upstream
/// bolts the two together in its `bind.js` and only the DOM half belongs to a browser. So the four
/// shapes the engine reports become a `Toggle`, a `Slider`, a `Picker` and a `TextField`, and nothing
/// here re-derives what the control means.
///
/// Every value crosses the boundary through `ForeignSignals`, because `VegaValue`'s cases are value
/// classes and are absent from the Obj-C header: without it a host could draw a slider and have no way
/// to say where the reader put it.
struct ChartControls: View {
  let controls: [SignalInput]
  let session: ChartSession

  var body: some View {
    VStack(alignment: .leading, spacing: 14) {
      Text("Controls").font(.headline)
      ForEach(Array(controls.enumerated()), id: \.offset) { _, input in
        control(for: input)
      }
    }
  }

  @ViewBuilder
  private func control(for input: SignalInput) -> some View {
    let signals = ForeignSignals.shared
    switch signals.bindKind(bind: input.bind) {
    case "checkbox":
      Toggle(
        input.label,
        isOn: Binding(
          get: { signals.boolean(value: session.value(of: input))?.boolValue ?? false },
          set: { session.set(signal: input.signal, to: signals.ofBoolean(value: $0)) }
        )
      )

    case "range":
      // The bounds are resolved by the engine: a specification may give none of min, max or step and
      // upstream fills them in from the signal's own value.
      let bounds = signals.rangeBounds(bind: input.bind)?.map { $0.doubleValue } ?? [0, 100, 1]
      let current = signals.number(value: session.value(of: input))?.doubleValue ?? bounds[0]
      VStack(alignment: .leading, spacing: 2) {
        HStack {
          Text(input.label)
          Spacer()
          Text(format(current)).foregroundStyle(.secondary).monospacedDigit()
        }
        .font(.callout)
        Slider(
          value: Binding(
            get: { current },
            set: { session.set(signal: input.signal, to: signals.ofNumber(value: $0)) }
          ),
          in: bounds[0]...max(bounds[1], bounds[0] + .ulpOfOne),
          step: bounds[2] > 0 ? bounds[2] : 1
        )
      }

    case "choice":
      let options = signals.choiceOptions(bind: input.bind) ?? []
      let labels = signals.choiceLabels(bind: input.bind) ?? []
      let selected = selectedIndex(of: input, options: options)
      VStack(alignment: .leading, spacing: 4) {
        Text(input.label).font(.callout)
        let picker = Picker(
          input.label,
          selection: Binding(
            get: { selected },
            set: { index in
              guard options.indices.contains(index) else { return }
              session.set(signal: input.signal, to: options[index])
            }
          )
        ) {
          ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
            Text(label).tag(index)
          }
        }
        // Radio buttons have no iOS equivalent, and a segmented control is what the platform uses
        // where a browser would draw them: a small fixed set, all visible at once. The two styles are
        // different *types*, so the choice is made over the whole view rather than in the argument.
        if signals.isRadio(bind: input.bind) && labels.count <= 4 {
          picker.pickerStyle(.segmented)
        } else {
          picker.pickerStyle(.menu)
        }
      }

    case "field":
      VStack(alignment: .leading, spacing: 4) {
        Text(input.label).font(.callout)
        TextField(
          input.label,
          text: Binding(
            get: { signals.text(value: session.value(of: input)) },
            set: { session.set(signal: input.signal, to: signals.ofString(value: $0)) }
          )
        )
        .textFieldStyle(.roundedBorder)
        .autocorrectionDisabled()
      }

    default:
      // A control kind this app has not been taught. Saying so beats drawing nothing, which would
      // look like the specification had asked for nothing.
      Text("\(input.label): unsupported control")
        .font(.caption)
        .foregroundStyle(.orange)
    }
  }

  /// Which option is selected, preferring the reader's own change over the compiled value.
  private func selectedIndex(of input: SignalInput, options: [VegaValue]) -> Int {
    let signals = ForeignSignals.shared
    let current = signals.text(value: session.value(of: input))
    return options.firstIndex { signals.text(value: $0) == current } ?? 0
  }

  private func format(_ value: Double) -> String {
    value == value.rounded() && abs(value) < 1e15
      ? String(Int(value))
      : String(format: "%.4g", value)
  }
}
