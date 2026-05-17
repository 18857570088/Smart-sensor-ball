import Foundation

struct SensorBallAPIClient {
    let baseURL = URL(string: "http://152.136.62.157/sensorball/api/v1/")!
    private let session: URLSession = .shared

    func fetchLeaderboard() async throws -> Data {
        try await get(path: "leaderboard")
    }

    func uploadTrainingRecord(totalHits: Int, durationSeconds: Int, endedAt: Date = Date()) async throws -> Data {
        var payload: [String: Any] = [
            "total_hits": totalHits,
            "duration_seconds": durationSeconds,
            "ended_at_epoch_ms": Int(endedAt.timeIntervalSince1970 * 1000),
            "source": "ios",
        ]
        payload["mode"] = durationSeconds == 60 ? "seconds_60" : "seconds_30"
        return try await post(path: "training-records", payload: payload)
    }

    private func get(path: String) async throws -> Data {
        let request = URLRequest(url: baseURL.appendingPathComponent(path))
        let (data, response) = try await session.data(for: request)
        try validate(response)
        return data
    }

    private func post(path: String, payload: [String: Any]) async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await session.data(for: request)
        try validate(response)
        return data
    }

    private func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }
}

