import React from "react";
import { StyleSheet, Text, View } from "react-native";

type Props = {
  direction: string;
  distance: number;
  rotation?: number; // degrees
};

function formatDistance(m: number) {
  if (m > 1000) return (m / 1000).toFixed(1) + " km";
  return Math.round(m) + " m";
}

export function ArrowRenderer({ direction, distance, rotation = 0 }: Props) {
  return (
    <View style={styles.container}>
      
      {/* Arrow */}
      <Text
        style={[
          styles.arrow,
          {
            transform: [{ rotate: `${rotation}deg` }],
          },
        ]}
      >
        ↑
      </Text>

      {/* Instruction */}
      <Text style={styles.text}>{direction}</Text>

      {/* Distance */}
      <Text style={styles.distance}>{formatDistance(distance)}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    justifyContent: "center",
  },

  arrow: {
    fontSize: 90,
    color: "#00E5FF",
  },

  text: {
    color: "white",
    fontSize: 18,
    marginTop: 10,
  },

  distance: {
    color: "#00E5FF",
    fontSize: 16,
  },
});