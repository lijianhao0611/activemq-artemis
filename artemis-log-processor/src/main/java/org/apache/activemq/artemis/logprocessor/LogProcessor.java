/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.logprocessor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import org.apache.activemq.artemis.logprocessor.annotation.LogBundle;
import org.apache.activemq.artemis.logprocessor.annotation.GetLogger;
import org.apache.activemq.artemis.logprocessor.annotation.LogMessage;
import org.apache.activemq.artemis.logprocessor.annotation.Message;

@SupportedAnnotationTypes({"org.apache.activemq.artemis.logprocessor.annotation.LogBundle"})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class LogProcessor extends AbstractProcessor {
   private static final boolean DEBUG;

   static {
      boolean debugResult = false;
      try {
         String debugEnvVariable = System.getenv("ARTEMIS_LOG_PROCESSOR_DEBUG");
         if (debugEnvVariable != null) {
            debugResult = Boolean.parseBoolean(debugEnvVariable);
         }
      } catch (Exception e) {
         e.printStackTrace();
      }
      DEBUG = debugResult;
   }

   /**
    * define a system variable ARTEMIS_LOG_PROCESSOR_DEBUG=true in order to see debug output
    */
   protected static void debug(String debugMessage) {
      if (DEBUG) {
         System.out.println(debugMessage);
      }
   }



   @Override
   public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      HashMap<Integer, String> messages = new HashMap<>();

      try {


         for (TypeElement annotation : annotations) {
            for (Element annotatedTypeEl : roundEnv.getElementsAnnotatedWith(annotation)) {
               TypeElement annotatedType = (TypeElement) annotatedTypeEl;

               LogBundle bundleAnnotation = annotatedType.getAnnotation(LogBundle.class);

               String fullClassName = annotatedType.getQualifiedName() + "_impl";
               String interfaceName = annotatedType.getSimpleName().toString();
               String simpleClassName = interfaceName + "_impl";
               JavaFileObject fileObject = processingEnv.getFiler().createSourceFile(fullClassName);

               if (DEBUG) {
                  debug("");
                  debug("*******************************************************************************************************************************");
                  debug("processing " + fullClassName + ", generating: " + fileObject.getName());
               }

               PrintWriter writerOutput = new PrintWriter(fileObject.openWriter());

               // header
               writerOutput.println("/** This class is auto generated by " + LogProcessor.class.getCanonicalName());
               writerOutput.println("    and it inherits whatever license is declared at " + annotatedType + " */");
               writerOutput.println();

               // opening package
               writerOutput.println("package " + annotatedType.getEnclosingElement() + ";");
               writerOutput.println();

               writerOutput.println("import org.slf4j.Logger;");
               writerOutput.println("import org.slf4j.LoggerFactory;");
               writerOutput.println("import org.slf4j.helpers.FormattingTuple;");
               writerOutput.println("import org.slf4j.helpers.MessageFormatter;");

               writerOutput.println();

               // Opening class
               writerOutput.println("// " + bundleAnnotation.toString());
               writerOutput.println("public class " + simpleClassName + " implements " + interfaceName);
               writerOutput.println("{");

               writerOutput.println("   private final Logger logger;");
               writerOutput.println();

               writerOutput.println("   public " + simpleClassName + "(Logger logger ) {");
               writerOutput.println("      this.logger = logger;");
               writerOutput.println("   }");
               writerOutput.println();

               for (Element el : annotatedType.getEnclosedElements()) {
                  if (el.getKind() == ElementKind.METHOD) {

                     ExecutableElement executableMember = (ExecutableElement) el;

                     Message messageAnnotation = el.getAnnotation(Message.class);
                     LogMessage logAnnotation = el.getAnnotation(LogMessage.class);
                     GetLogger getLogger = el.getAnnotation(GetLogger.class);

                     if (DEBUG) {
                        debug("Generating " + executableMember);
                     }

                     int generatedPaths = 0;

                     if (messageAnnotation != null) {
                        generatedPaths++;
                        if (DEBUG) {
                           debug("... annotated with " + messageAnnotation);
                        }
                        generateMessage(bundleAnnotation, writerOutput, executableMember, messageAnnotation, messages);
                     }

                     if (logAnnotation != null) {
                        generatedPaths++;
                        if (DEBUG) {
                           debug("... annotated with " + logAnnotation);
                        }
                        generateLogger(bundleAnnotation, writerOutput, executableMember, logAnnotation, messages);
                     }

                     if (getLogger != null) {
                        generatedPaths++;
                        debug("... annotated with " + getLogger);
                        generateGetLogger(bundleAnnotation, writerOutput, executableMember, getLogger);
                     }

                     if (generatedPaths > 1) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot use combined annotations  on " + executableMember);
                        return false;
                     }


                  }
               }

               writerOutput.println("}");
               writerOutput.close();

               if (DEBUG) {
                  debug("done processing " + fullClassName);
                  debug("*******************************************************************************************************************************");
                  debug("");
               }
            }
         }
      } catch (Exception e) {
         e.printStackTrace();
         processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
         return false;
      }

      return true;
   }

   private void generateMessage(LogBundle bundleAnnotation,
                                PrintWriter writerOutput,
                                ExecutableElement executableMember,
                                Message messageAnnotation,
                                HashMap<Integer, String> processedMessages) {

      String previousMessage = processedMessages.get(messageAnnotation.id()); // Could move inside the if?

      if (processedMessages.containsKey(messageAnnotation.id())) {
         throw new IllegalStateException("message " + messageAnnotation.id() + " with definition = " + messageAnnotation.value() + " was previously defined as " + previousMessage);
      }

      processedMessages.put(messageAnnotation.id(), messageAnnotation.value());

      // This is really a debug output
      writerOutput.println("   // " + encodeSpecialChars(messageAnnotation.toString()));

      writerOutput.println("   @Override");
      writerOutput.write("   public " + executableMember.getReturnType() + " " + executableMember.getSimpleName() + "(");

      Iterator<? extends VariableElement> parameters = executableMember.getParameters().iterator();

      boolean hasParameters = false;


      VariableElement exceptionParameter = null;
      // the one that will be used on the call
      StringBuffer callList = new StringBuffer();
      while (parameters.hasNext()) {
         hasParameters = true;
         VariableElement parameter = parameters.next();
         writerOutput.write(parameter.asType() + " " + parameter.getSimpleName());
         callList.append(parameter.getSimpleName());
         if (parameters.hasNext()) {
            writerOutput.write(", ");
            callList.append(",");
         }

         if (isException(parameter.asType(), parameter)) {
            if (exceptionParameter != null) {
               throw new IllegalStateException("messageAnnotation " + messageAnnotation.value() + " has two exceptions defined on the method");
            }
            exceptionParameter = parameter;
         }
      }

      // the real implementation
      writerOutput.println(")");
      writerOutput.println("   {");

      String formattingString = encodeSpecialChars(bundleAnnotation.projectCode() + messageAnnotation.id() + " " + messageAnnotation.value());
      if (!hasParameters) {
         writerOutput.println("     String returnString = \"" + formattingString + "\";");
      } else {
         writerOutput.println("     String returnString = MessageFormatter.arrayFormat(\"" + formattingString + "\", new Object[]{" + callList + "}).getMessage();");
      }

      if (executableMember.getReturnType().toString().equals(String.class.getName())) {
         writerOutput.println("     return returnString;");
      } else {
         writerOutput.println();
         writerOutput.println("      {");
         String exceptionVariableName = "objReturn_" + executableMember.getSimpleName();
         writerOutput.println("         " + executableMember.getReturnType().toString() + " " + exceptionVariableName + " = new " + executableMember.getReturnType().toString() + "(returnString);");

         if (exceptionParameter != null) {
            writerOutput.println("         " + exceptionVariableName + ".initCause(" + exceptionParameter.getSimpleName() + ");");
         }
         writerOutput.println("         return " + exceptionVariableName + ";");
         writerOutput.println("      }");
      }

      writerOutput.println("   }");
      writerOutput.println();
   }

   boolean isException(TypeMirror parameterType, VariableElement methodParameter) {
      if (parameterType == null) {
         // This should never happen, but my OCD can't help here, I'm adding this just in case
         return false;
      }

      if (DEBUG && methodParameter != null) {
         debug("... checking if parameter \"" + parameterType + " " + methodParameter + "\" is an exception");
      }

      String parameterClazz = parameterType.toString();
      if (parameterClazz.equals("java.lang.Throwable") || parameterClazz.endsWith("Exception")) { // bad luck if you named a class with Exception and it was not an exception ;)
         if (DEBUG) {
            debug("... Class " + parameterClazz + " was considered an exception");
         }
         return true;
      }

      switch(parameterClazz) {
         // some known types
         case "java.lang.String":
         case "java.lang.Object":
         case "java.lang.Long":
         case "java.lang.Integer":
         case "java.lang.Number":
         case "java.lang.Thread":
         case "java.lang.ThreadGroup":
         case "org.apache.activemq.artemis.api.core.SimpleString":
         case "none":
            if (DEBUG) {
               debug("... " + parameterClazz + " is a known type, not an exception!");
            }
            return false;
      }

      if (parameterType instanceof DeclaredType) {
         DeclaredType declaredType = (DeclaredType) parameterType;
         if (declaredType.asElement() instanceof TypeElement) {
            TypeElement theElement = (TypeElement) declaredType.asElement();
            if (DEBUG) {
               debug("... ... recursively inspecting super class for Exception on " + parameterClazz + ", looking at superClass " + theElement.getSuperclass());
            }
            return isException(theElement.getSuperclass(), null);
         }
      }
      return false;
   }

   private String encodeSpecialChars(String input) {
      return input.replaceAll("\n", "\\\\n").replaceAll("\"", "\\\\\"");
   }


   private void generateGetLogger(LogBundle bundleAnnotation,
                                  PrintWriter writerOutput,
                                  ExecutableElement executableMember,
                                  GetLogger loggerAnnotation) {

      // This is really a debug output
      writerOutput.println("   // " + loggerAnnotation.toString());
      writerOutput.println("   @Override");
      writerOutput.println("   public Logger " + executableMember.getSimpleName() + "() { return logger; }");
      writerOutput.println();
   }


   private void generateLogger(LogBundle bundleAnnotation,
                               PrintWriter writerOutput,
                               ExecutableElement executableMember,
                               LogMessage messageAnnotation,
                               HashMap<Integer, String> processedMessages) {

      String previousMessage = processedMessages.get(messageAnnotation.id());

      if (processedMessages.containsKey(messageAnnotation.id())) {
         throw new IllegalStateException("message " + messageAnnotation.id() + " with definition = " + messageAnnotation.value() + " was previously defined as " + previousMessage);
      }

      processedMessages.put(messageAnnotation.id(), messageAnnotation.value());

      // This is really a debug output
      writerOutput.println("   // " + encodeSpecialChars(messageAnnotation.toString()));
      writerOutput.println("   @Override");
      writerOutput.write("   public void " + executableMember.getSimpleName() + "(");

      Iterator<? extends VariableElement> parameters = executableMember.getParameters().iterator();

      boolean hasParameters = false;

      // the one that will be used on the call
      StringBuffer callList = new StringBuffer();
      while (parameters.hasNext()) {
         hasParameters = true;
         VariableElement parameter = parameters.next();
         writerOutput.write(parameter.asType() + " " + parameter.getSimpleName());
         callList.append(parameter.getSimpleName());
         if (parameters.hasNext()) {
            writerOutput.write(", ");
            callList.append(",");
         }
      }

      // the real implementation
      writerOutput.println(")");
      writerOutput.println("   {");

      String methodName;

      switch (messageAnnotation.level()) {
         case WARN:
            methodName = "warn"; break;
         case INFO:
            methodName = "info"; break;
         case ERROR:
            methodName = "error"; break;
         case DEBUG: // TODO remove this
            methodName = "debug"; break;
         case TRACE: // TODO remove this
            methodName = "trace"; break;
         default:
            throw new IllegalStateException("illegal method level " + messageAnnotation.level());
      }

      //TODO: handle causes being passed in the args to be logged, but not necessarily (often not) being last arg at present as SLF4J/frameworks expect.
      String formattingString = encodeSpecialChars(bundleAnnotation.projectCode() + messageAnnotation.id() + " " + messageAnnotation.value());
      if (!hasParameters) {
         writerOutput.println("      logger." + methodName + "(\"" + formattingString + "\");");
      } else {
         writerOutput.println("      logger." + methodName + "(\"" + formattingString + "\", " + callList + ");");
      }
      writerOutput.println("   }");
      writerOutput.println();
   }
}
