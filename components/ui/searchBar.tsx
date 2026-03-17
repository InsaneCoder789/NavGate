import React, { useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from "react-native";

import {
  AutocompleteService,
  SearchResult,
} from "../../services/search/autocomplete";
import { Geocoder } from "../../services/search/geocoder";

type Props = {
  onSelectDestination: (coords: {
    latitude: number;
    longitude: number;
  }) => void;
  onSelectOrigin?: (coords: {
    latitude: number;
    longitude: number;
  }) => void;
  userLocation?: { latitude: number; longitude: number };
};

const auto = new AutocompleteService();
const geo = new Geocoder();

export default function SearchBar({
  onSelectDestination,
  onSelectOrigin,
  userLocation,
}: Props) {
  const [originQuery, setOriginQuery] = useState("Your location");
  const [destinationQuery, setDestinationQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const debounceRef = useRef<any>(null);

  const [mode, setMode] = useState<"destination" | "origin">("destination");

  // 🔥 AUTO SET CURRENT LOCATION
  useEffect(() => {
    if (userLocation && onSelectOrigin) {
      onSelectOrigin(userLocation);
    }
  }, [userLocation]);

  function search(text: string) {
    if (mode === "origin") {
      setOriginQuery(text);
    } else {
      setDestinationQuery(text);
    }

    if (debounceRef.current) clearTimeout(debounceRef.current);

    debounceRef.current = setTimeout(async () => {
      if (text.length < 2) {
        setResults([]);
        return;
      }

      setLoading(true);
      const res = await auto.search(text);
      setResults(res);
      setLoading(false);
    }, 300);
  }

  async function handleSubmit() {
    const activeQuery =
      mode === "origin" ? originQuery : destinationQuery;

    if (!activeQuery || activeQuery.trim().length < 2) return;

    try {
      setLoading(true);
      const res = await auto.search(activeQuery.trim());
      setLoading(false);

      if (res && res.length > 0) {
        handleSelect(res[0]);
      }
    } catch {
      setLoading(false);
    }
  }

  function handleSelect(item: SearchResult) {
    const coords = geo.getCoordinates(item);

    if (!coords) return;

    if (mode === "origin" && onSelectOrigin) {
      onSelectOrigin(coords);
      setOriginQuery(item.display);
    } else {
      onSelectDestination(coords);
      setDestinationQuery(item.display);
    }

    setResults([]);
  }

  function clearInput() {
    if (mode === "origin") {
      setOriginQuery("");
    } else {
      setDestinationQuery("");
    }
    setResults([]);
  }

  return (
    <View style={styles.container}>
      {/* FROM */}
      <View style={styles.rowInput}>
        <Text style={styles.icon}>📍</Text>
        <TextInput
          placeholder="Your location"
          value={originQuery}
          onFocus={() => setMode("origin")}
          onChangeText={search}
          onSubmitEditing={handleSubmit}
          style={styles.input}
          placeholderTextColor="#999"
        />
        {!!originQuery && (
          <TouchableOpacity onPress={clearInput}>
            <Text style={styles.clear}>×</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* TO */}
      <View style={styles.rowInput}>
        <Text style={styles.icon}>🔍</Text>
        <TextInput
          placeholder="Where to?"
          value={destinationQuery}
          onFocus={() => setMode("destination")}
          onChangeText={search}
          onSubmitEditing={handleSubmit}
          style={styles.input}
          placeholderTextColor="#999"
        />
        {!!destinationQuery && (
          <TouchableOpacity onPress={clearInput}>
            <Text style={styles.clear}>×</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* LOADER */}
      {loading && (
        <View style={styles.loader}>
          <ActivityIndicator size="small" />
        </View>
      )}

      {/* RESULTS */}
      {results.length > 0 && (
        <FlatList
          data={results}
          keyExtractor={(item, index) => index.toString()}
          keyboardShouldPersistTaps="handled"
          renderItem={({ item }) => (
            <TouchableOpacity
              style={styles.result}
              onPress={() => handleSelect(item)}
            >
              <Text numberOfLines={2} style={styles.resultText}>
                {item.display}
              </Text>
            </TouchableOpacity>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    top: 60,
    left: 16,
    right: 16,
    backgroundColor: "#fff",
    borderRadius: 14,
    padding: 12,
    elevation: 8,
    zIndex: 1000,
  },

  rowInput: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#f5f5f5",
    borderRadius: 10,
    paddingHorizontal: 10,
    marginBottom: 8,
  },

  input: {
    fontSize: 16,
    flex: 1,
    color: "#000",
  },

  icon: {
    fontSize: 16,
    marginRight: 8,
  },

  clear: {
    fontSize: 18,
    color: "#999",
    paddingHorizontal: 8,
  },

  loader: {
    marginTop: 10,
  },

  result: {
    paddingVertical: 10,
    borderBottomWidth: 0.5,
    borderColor: "#eee",
  },

  resultText: {
    fontSize: 14,
    color: "#333",
  },
});