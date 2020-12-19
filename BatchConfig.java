package com.swt.batchwriters;


import com.swt.batchwriters.listener.ProductSkipListener;
import com.swt.batchwriters.model.Product;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.step.skip.AlwaysSkipItemSkipPolicy;
import org.springframework.batch.item.database.ItemPreparedStatementSetter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.*;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.BeanWrapperFieldExtractor;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.xml.StaxEventItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.oxm.xstream.XStreamMarshaller;
import processor.ProductProcessor;

import javax.sql.DataSource;
import javax.xml.crypto.Data;
import java.io.IOException;
import java.io.Writer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

@EnableBatchProcessing
@Configuration
public class BatchConfig {

    @Autowired
    private StepBuilderFactory steps;

    @Autowired
    private JobBuilderFactory jobs;

    @Autowired
    private DataSource dataSource;

    @Bean
    @StepScope
    public FlatFileItemReader reader(
            @Value( "#{jobParameters['fileInput']}"  ) FileSystemResource inputFile
            ){

        FlatFileItemReader reader = new FlatFileItemReader();
        reader.setResource(inputFile);
        reader.setLinesToSkip(1);
        reader.setLineMapper(new DefaultLineMapper(){
            {
                setFieldSetMapper(new BeanWrapperFieldSetMapper(){
                    {
                        setTargetType(Product.class);
                    }
                });

                setLineTokenizer(new DelimitedLineTokenizer(){
                    {
                        setNames(new String[]{"productId","prodName","productDesc"  ,"price","unit"});
                        setDelimiter(",");
                    }
                });
            }
        });

        return reader;
    }

    @Bean
    @StepScope
   public StaxEventItemWriter xmlWriter( @Value("#{jobParameters['fileOutput']}" )FileSystemResource outputFile){

        XStreamMarshaller marshaller = new XStreamMarshaller();
        HashMap<String,Class> aliases = new HashMap<>();
        aliases.put("product",Product.class);
        marshaller.setAliases(aliases);
        marshaller.setAutodetectAnnotations(true);


        StaxEventItemWriter staxEventItemWriter = new StaxEventItemWriter();

        staxEventItemWriter.setResource(outputFile);
        staxEventItemWriter.setMarshaller(marshaller);
        staxEventItemWriter.setRootTagName("Products");

        return staxEventItemWriter;


   }

   @Bean
   @StepScope
   public FlatFileItemWriter<Product> flatFileItemWriter(
           @Value("#{jobParameters['fileOutput']}" )FileSystemResource outputFile
     ){

        FlatFileItemWriter writer = new FlatFileItemWriter<Product>(){
            @Override
            public String doWrite(List<? extends Product> items) {
                for(Product p :items){
                    if (p.getProductId() == 9 )
                        throw new RuntimeException("Because ID is 9");
                }
                return super.doWrite(items);
            }
        };

        writer.setResource(outputFile);
        writer.setLineAggregator( new DelimitedLineAggregator(){
            {
                setDelimiter("|");
                setFieldExtractor(new BeanWrapperFieldExtractor(){
                    {
                        setNames(new String[]{"productId","prodName","productDesc","price","unit" });

                    }
                });
            }
        });

        // how to write the header
        writer.setHeaderCallback(new FlatFileHeaderCallback() {
            @Override
            public void writeHeader(Writer writer) throws IOException {
                writer.write("productID,productName,ProductDesc,price,unit");
            }
        });


        writer.setAppendAllowed(false);

        writer.setFooterCallback(new FlatFileFooterCallback() {
            @Override
            public void writeFooter(Writer writer) throws IOException {
                writer.write(" The file is created at " + new SimpleDateFormat().format(new Date()));
            }
        });
        return writer;
   }


    @Bean
    public JdbcBatchItemWriter dbWriter(){
        JdbcBatchItemWriter writer = new JdbcBatchItemWriter ();
        writer.setDataSource(this.dataSource);
        writer.setSql("insert into products (product_id, prod_name, prod_desc, price, unit )" +
                " values (?, ?, ?, ? , ? ) ");
        writer.setItemPreparedStatementSetter(new ItemPreparedStatementSetter<Product>() {
            @Override
            public void setValues(Product item, PreparedStatement ps) throws SQLException {
                ps.setInt(1,item.getProductId());
                ps.setString(2,item.getProdName());
                ps.setString(3, item.getProductDesc());
                ps.setBigDecimal(4, item.getPrice());
                ps.setInt(5, item.getUnit());
            }
        });

        return writer;

    }

    @Bean
    public JdbcBatchItemWriter dbWriter2(){
        return new JdbcBatchItemWriterBuilder<Product>()
                .dataSource(this.dataSource)
                .sql("insert into products (product_id, prod_name, prod_desc, price, unit )" +
                        " values ( :productId, :prodName, :productDesc, :price, :unit ) ")
                .beanMapped()
                .build();
    }

   @Bean
   public Step step1(){
        return steps.get("step1")
                .<Product,Product>chunk(3)
                .reader(reader(null))
                .processor(new ProductProcessor())
                .writer(flatFileItemWriter(null))
               //  .writer(xmlWriter(null))
               // .writer(dbWriter())
               //   .writer(dbWriter2())
                .faultTolerant()
               // .skip(RuntimeException.class)
               // .skipLimit(10)
                //Skippolicy
                .skipPolicy(new AlwaysSkipItemSkipPolicy())
                .listener(new ProductSkipListener())
                .build();
   }

   @Bean
   public Job job1(){
        return jobs.get("job1")
                .incrementer(new RunIdIncrementer())
                .start(step1())
                .build();
   }

}
