/*
   Copyright (c) 2020 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.tools.clientgen;

import com.linkedin.data.schema.DataSchemaResolver;
import com.linkedin.data.schema.resolver.MultiFormatDataSchemaResolver;
import com.linkedin.internal.tools.ArgumentFileProcessor;
import com.linkedin.pegasus.generator.CodeUtil;
import com.linkedin.pegasus.generator.TemplateSpecGenerator;
import com.linkedin.restli.internal.server.RestLiInternalException;
import com.linkedin.restli.restspec.ResourceEntityType;
import com.linkedin.restli.restspec.ResourceSchema;
import com.linkedin.restli.tools.clientgen.fluentspec.AssociationResourceSpec;
import com.linkedin.restli.tools.clientgen.fluentspec.BaseResourceSpec;
import com.linkedin.restli.tools.clientgen.fluentspec.CollectionResourceSpec;
import com.linkedin.restli.tools.clientgen.fluentspec.SimpleResourceSpec;
import com.linkedin.restli.tools.clientgen.fluentspec.SpecUtils;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.JarResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Generate fluent api client bindings from idl file to java source file.
 *
 * @author Karthik Balasubramanian
 */
public class FluentApiGenerator
{
  private static final Logger LOGGER = LoggerFactory.getLogger(FluentApiGenerator.class);
  private static final Options OPTIONS = new Options();
  private static final String API_TEMPLATE_DIR = "apiVmTemplates";
  private static final String FLUENT_CLIENT_FILE_SUFFIX = "FluentClient";

  public static void main(String[] args) throws Exception
  {
    OPTIONS.addOption("h", "help", false, "Show help.");
    OptionBuilder.withArgName("Directory");
    OptionBuilder.withLongOpt("targetDir");
    OptionBuilder.hasArgs(1);
    OptionBuilder.isRequired();
    OptionBuilder.withDescription("Target directory in which the classes should be generated.");
    OPTIONS.addOption(OptionBuilder.create('t'));
    OptionBuilder.withArgName("Path|ArgFile");
    OptionBuilder.withLongOpt("resolverPath");
    OptionBuilder.hasArgs(1);
    OptionBuilder.isRequired();
    OptionBuilder.withDescription("Resolver path for loading data schemas. This can also be an arg file with path written per "
        + "line in the file. Use the syntax @[filename] for this arg when using the arg file.");
    OPTIONS.addOption(OptionBuilder.create('p'));
    OptionBuilder.withArgName("Path");
    OptionBuilder.withLongOpt("rootPath");
    OptionBuilder.hasArgs(1);
    OptionBuilder.withDescription("Root path to use for generating relative path for source location");
    OPTIONS.addOption(OptionBuilder.create('r'));

    try
    {
      final CommandLineParser parser = new GnuParser();
      CommandLine cl = parser.parse(OPTIONS, args);

      if (cl.hasOption('h'))
      {
        help();
        System.exit(0);
      }
      String targetDirectory = cl.getOptionValue('t');
      String resolverPath = cl.getOptionValue('p');
      if (ArgumentFileProcessor.isArgFile(resolverPath))
      {
        resolverPath = ArgumentFileProcessor.getContentsAsArray(resolverPath)[0];
      }
      String[] sources = cl.getArgs();
      if (sources.length == 1 && ArgumentFileProcessor.isArgFile(sources[0]))
      {
        // Using argFile, prefixed with '@' and containing one absolute path per line
        // Consume the argFile and populate the sources array
        sources = ArgumentFileProcessor.getContentsAsArray(sources[0]);
      }

      FluentApiGenerator.run(resolverPath, cl.getOptionValue('r'), targetDirectory, sources);
    }
    catch (ParseException e)
    {
      LOGGER.error("Invalid arguments: " + e.getMessage());
      help();
      System.exit(1);
    }

  }

  static void run(String resolverPath, String rootPath, String targetDirectoryPath, String[] sources)
      throws IOException
  {
    final RestSpecParser parser = new RestSpecParser();
    final DataSchemaResolver schemaResolver = MultiFormatDataSchemaResolver.withBuiltinFormats(resolverPath);
    VelocityEngine velocityEngine = initVelocityEngine();
    final File targetDirectory = new File(targetDirectoryPath);


    final RestSpecParser.ParseResult parseResult = parser.parseSources(sources);

    final StringBuilder message = new StringBuilder();
    for (CodeUtil.Pair<ResourceSchema, File> pair : parseResult.getSchemaAndFiles())
    {
      ResourceSchema resourceSchema = pair.first;
      // Skip unstructured data resources for client generation
      if (resourceSchema != null && ResourceEntityType.UNSTRUCTURED_DATA == resourceSchema.getEntityType())
      {
        continue;
      }

      BaseResourceSpec spec = null;
      if (resourceSchema.hasCollection())
      {
        spec = new CollectionResourceSpec(resourceSchema,
            new TemplateSpecGenerator(schemaResolver),
            pair.second.getPath(),
            schemaResolver);
      }
      else if (resourceSchema.hasSimple())
      {
        spec = new SimpleResourceSpec(resourceSchema,
            new TemplateSpecGenerator(schemaResolver),
            pair.second.getPath(),
            schemaResolver);
      }
      else if (resourceSchema.hasAssociation())
      {
        spec = new AssociationResourceSpec(resourceSchema,
            new TemplateSpecGenerator(schemaResolver),
            pair.second.getPath(),
            schemaResolver);
      }
      else
      {
        continue;
      }

      File packageDir = new File(targetDirectory, spec.getNamespace().toLowerCase().replace('.', File.separatorChar));
      packageDir.mkdirs();
      // Generate FluentClient impl
      File implFile = new File(packageDir, CodeUtil.capitalize(spec.getResource().getName()) + FLUENT_CLIENT_FILE_SUFFIX + ".java");
      // Generate Resource interface
      // TODO: move to universal client generator where fits better
      File interfaceFile= new File(packageDir, CodeUtil.capitalize(spec.getResource().getName()) + ".java");
      for (Pair<File, String> templatePair : Arrays.asList(
          ImmutablePair.of(interfaceFile, "resource_interface.vm"),
          ImmutablePair.of(implFile, "resource.vm")
      ))
      {
        try (FileWriter writer = new FileWriter(templatePair.getLeft()))
        {
          VelocityContext context = new VelocityContext();
          context.put("spec", spec);
          context.put("util", SpecUtils.class);
          context.put("class_name_suffix", FLUENT_CLIENT_FILE_SUFFIX);
          if (spec.getResource().hasCollection()
              || spec.getResource().hasSimple()
              || spec.getResource().hasAssociation())
          {
            velocityEngine.mergeTemplate(API_TEMPLATE_DIR + "/" + templatePair.getRight(),
                VelocityEngine.ENCODING_DEFAULT,
                context,
                writer);
          }
        }
        catch (Exception e)
        {
          LOGGER.error("Error generating fluent client apis", e);
          message.append(e.getMessage()).append("\n");
        }
      }


    }

    if (message.length() > 0)
    {
      throw new IOException(message.toString());
    }

  }

  private static VelocityEngine initVelocityEngine()
  {
    final URL templateDirUrl = FluentApiGenerator.class.getClassLoader().getResource(API_TEMPLATE_DIR);
    if (templateDirUrl == null)
    {
      throw new RestLiInternalException("Unable to find the Velocity template resources");
    }

    StringBuilder configName;
    VelocityEngine velocity;
    if ("jar".equals(templateDirUrl.getProtocol()))
    {
      velocity = new VelocityEngine();

      // config Velocity to use the jar resource loader
      // more detail in Velocity user manual
      velocity.setProperty(VelocityEngine.RESOURCE_LOADER, "jar");

      configName = new StringBuilder("jar.").append(VelocityEngine.RESOURCE_LOADER).append(".class");
      velocity.setProperty(configName.toString(), JarResourceLoader.class.getName());

      configName = new StringBuilder("jar.").append(VelocityEngine.RESOURCE_LOADER).append(".path");

      // fix for Velocity 1.5: jar URL needs to be ended with "!/"
      final String normalizedUrl = templateDirUrl.toString().substring(0, templateDirUrl.toString().length() - API_TEMPLATE_DIR
          .length());
      velocity.setProperty(configName.toString(), normalizedUrl);
    }
    else if ("file".equals(templateDirUrl.getProtocol()))
    {
      velocity = new VelocityEngine();

      final String resourceDirPath = new File(templateDirUrl.getPath()).getParent();
      velocity.setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_PATH, resourceDirPath);
    }
    else
    {
      throw new IllegalArgumentException("Unsupported template path scheme");
    }
    velocity.setProperty(RuntimeConstants.SPACE_GOBBLING, RuntimeConstants.SpaceGobbling.STRUCTURED.name());
    velocity.setProperty(RuntimeConstants.VM_LIBRARY, "macros/library.vm");
    try
    {
      velocity.init();
    }
    catch (Exception e)
    {
      throw new RestLiInternalException(e);
    }
    return velocity;
  }

  private static void help()
  {
    final HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(120,
        FluentApiGenerator.class.getSimpleName(),
        "Command should be followed by one or more source files to process.",
        OPTIONS,
        "[sources]+          List of source files to process, specified at the end. Source file list can also be "
            + "provided as a single arg file, specified as @<arg filename>. The file should list source files one per line.",
        true);
  }
}
