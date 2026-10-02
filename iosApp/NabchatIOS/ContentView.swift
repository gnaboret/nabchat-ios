import SwiftUI
import NabchatShared

struct ContentView: View {
    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.045, blue: 0.040)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                Spacer()

                Text("nabchat")
                    .font(.system(size: 58, weight: .regular, design: .serif))
                    .foregroundStyle(Color(white: 0.94))

                Text(SharedCoreInfo.shared.status())
                    .font(.caption)
                    .foregroundStyle(Color(white: 0.55))

                Spacer()

                Text("powered by Gnaboret")
                    .font(.system(size: 10))
                    .foregroundStyle(Color(white: 0.42))
                    .padding(.bottom, 18)
            }
            .padding()
        }
    }
}

#Preview {
    ContentView()
}
