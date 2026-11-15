package com.mcmanager.core.data.db

import androidx.room.TypeConverter
import com.mcmanager.core.model.Engine

class Converters {
  @TypeConverter
  fun engineToString(engine: Engine): String = engine.name

  @TypeConverter
  fun stringToEngine(value: String): Engine =
    Engine.entries.firstOrNull { it.name == value } ?: Engine.VANILLA
}
