/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.java

import org.jetbrains.org.objectweb.asm.Opcodes

public open class JavaPlatformVersion(val classVersion: Int) {

    object JAVA_6 : JavaPlatformVersion(Opcodes.V1_6)

    object JAVA_8 : JavaPlatformVersion(Opcodes.V1_8)

    companion  object {
        fun getDefault(): JavaPlatformVersion {
            return JAVA_6
        }
    }
}