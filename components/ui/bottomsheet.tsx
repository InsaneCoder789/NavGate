import React from "react";
import { StyleSheet, Text, View } from "react-native";

export default function BottomSheet({ children }: { children?: React.ReactNode }) {
  return (
    <View style={styles.container}>
      <View style={styles.handle} />
      <View style={styles.content}>
        {children || <Text style={styles.placeholder}>Bottom Sheet</Text>}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: "#111",
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingTop: 10,
    paddingBottom: 20,
    elevation: 10,
  },

  handle: {
    width: 40,
    height: 5,
    borderRadius: 3,
    backgroundColor: "#666",
    alignSelf: "center",
    marginBottom: 10,
  },

  content: {
    paddingHorizontal: 16,
  },

  placeholder: {
    color: "#aaa",
    textAlign: "center",
  },
});
