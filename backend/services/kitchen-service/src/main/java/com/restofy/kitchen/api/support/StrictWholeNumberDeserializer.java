package com.restofy.kitchen.api.support;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads an {@code Integer} only from a JSON whole number. JSON's default is forgiving: 1.5 would silently become 1 and
 * the text "2" would silently become 2. For a count of portions that is a lie about what the user typed, so anything
 * but a whole number is rejected (400) instead of being rounded or converted; a number beyond the int range is rejected too.
 */
public final class StrictWholeNumberDeserializer extends ValueDeserializer<Integer> {

	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
			return (Integer) context.handleUnexpectedToken(Integer.class, parser);
		}
		return parser.getIntValue();
	}
}
