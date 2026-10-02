import SwiftUI

@main
struct NabchatIOSApp: App {
    @State private var showingSplash = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                if showingSplash {
                    BrandSplashView()
                        .transition(.opacity)
                        .zIndex(10)
                }
            }
            .task {
                try? await Task.sleep(nanoseconds: 900_000_000)
                withAnimation(.easeOut(duration: 0.28)) { showingSplash = false }
            }
        }
    }
}

private struct BrandSplashView: View {
    var body: some View {
        ZStack {
            NabColors.background.ignoresSafeArea()
            Text("nabchat")
                .font(.system(size: 48, weight: .semibold, design: .serif))
                .foregroundStyle(NabColors.text)
            VStack {
                Spacer()
                VStack(spacing: 3) {
                    Text("powered by").font(.system(size: 9)).foregroundStyle(NabColors.secondary)
                    Text("Gnaboret").font(.system(size: 13, weight: .medium, design: .serif)).foregroundStyle(NabColors.text)
                }
                .padding(.bottom, 24)
            }
        }
    }
}
