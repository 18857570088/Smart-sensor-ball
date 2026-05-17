// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SmartSensorBall",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(name: "SmartSensorBall", targets: ["SmartSensorBall"]),
    ],
    targets: [
        .target(
            name: "SmartSensorBall",
            path: "SmartSensorBall",
            resources: [
                .process("Resources"),
                .process("Info.plist"),
            ]
        ),
    ]
)

