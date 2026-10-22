import RiffCore
import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 20) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 128, height: 128)
            Text("Welcome to Riff")
                .font(.largeTitle.bold())
            Text("Log in with your Spotify account to start listening. A Spotify Premium subscription is required.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 380)

            if let error = model.loginError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }

            if model.session == .waitingForBrowser {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Finish logging in with your browser.")
                        .foregroundStyle(.secondary)
                    Button("Open the Browser Again") { model.core.startLogin() }
                        .buttonStyle(.link)
                }
            } else {
                Button {
                    model.loginError = nil
                    model.core.startLogin()
                } label: {
                    Text("Log in with Spotify")
                        .frame(minWidth: 200)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
