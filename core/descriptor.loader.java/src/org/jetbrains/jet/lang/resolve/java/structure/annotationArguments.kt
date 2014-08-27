/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.resolve.java.structure

import org.jetbrains.jet.lang.resolve.name.Name

public trait JavaAnnotationArgument : JavaElement {
    public val name: Name?
}

public trait JavaLiteralAnnotationArgument : JavaAnnotationArgument {
    public val value: Any?
}

public trait JavaArrayAnnotationArgument : JavaAnnotationArgument {
    public fun getElements(): List<JavaAnnotationArgument>
}

public trait JavaEnumValueAnnotationArgument : JavaAnnotationArgument {
    public fun resolve(): JavaField?
}

public trait JavaClassObjectAnnotationArgument : JavaAnnotationArgument {
    public fun getReferencedType(): JavaType
}

public trait JavaAnnotationAsAnnotationArgument : JavaAnnotationArgument {
    public fun getAnnotation(): JavaAnnotation
}
