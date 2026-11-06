package io.geoverse.bench;

import static io.tileverse.parquetry.filter.Pred.col;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.Bbox;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.filter.Projection;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.storage.RangeReader;
import io.tileverse.storage.Storage;
import io.tileverse.storage.cache.CachingRangeReader;
import io.tileverse.storage.s3.S3StorageProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class ParquetryR2MemoryBench {
 record Q(String name,double minx,double miny,double maxx,double maxy){}
 static long[] procMem() throws IOException {
   long rss=0,hwm=0;
   for(String line:Files.readAllLines(Path.of("/proc/self/status"))){
     if(line.startsWith("VmRSS:")) rss=Long.parseLong(line.trim().split("\\s+")[1])*1024;
     if(line.startsWith("VmHWM:")) hwm=Long.parseLong(line.trim().split("\\s+")[1])*1024;
   }
   return new long[]{rss,hwm};
 }
 static long heapUsed(){
   Runtime r=Runtime.getRuntime();
   return r.totalMemory()-r.freeMemory();
 }
 public static void main(String[] a)throws Exception{
   if(a.length!=6) throw new IllegalArgumentException("BUCKET OBJECT WORKLOAD ENDPOINT REGION LAYOUT");
   String bucket=a[0], object=a[1], region=a[4], layout=a[5];
   URI endpoint=URI.create(a[3]);
   List<Q> qs=Files.readAllLines(Path.of(a[2])).stream().skip(1).filter(s->!s.isBlank()).map(s->{
     String[] x=s.split(","); return new Q(x[0],Double.parseDouble(x[1]),Double.parseDouble(x[2]),Double.parseDouble(x[3]),Double.parseDouble(x[4]));
   }).toList();
   S3AsyncClient client=S3AsyncClient.builder().endpointOverride(endpoint).region(Region.of(region))
     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(System.getenv("AWS_ACCESS_KEY_ID"),System.getenv("AWS_SECRET_ACCESS_KEY"))))
     .forcePathStyle(true).build();
   try(client; Storage storage=S3StorageProvider.open(URI.create("s3://"+bucket+"/"),client)){
     RangeReader base=storage.openRangeReader(object);
     CachingRangeReader cached=CachingRangeReader.builder(base).build();
     try(cached; ByteRangeSource src=ByteRangeSource.of(cached)){
       ParquetSource ds=ParquetSource.open(src);
       Projection pr=Projection.ofPhysical(List.of(ColumnPath.of("id"),ColumnPath.of("geometry"),ColumnPath.of("bbox")));
       long baseRss=procMem()[0];
       System.out.println("engine,layout,position,query,rows,elapsed_ms,rss_bytes,rss_delta_bytes,peak_rss_bytes,heap_used_bytes,cache_estimated_bytes,cache_entries,cache_hits,cache_misses");
       int pos=0;
       for(Q q:qs){
         pos++;
         Predicate p=col("geometry").bboxIntersects(Bbox.of2d(q.minx,q.miny,q.maxx,q.maxy));
         long t=System.nanoTime(), rows;
         try(Stream<ParquetRecord> s=ds.read(p,pr,ReadOptions.DEFAULTS)){ rows=s.count(); }
         double ms=(System.nanoTime()-t)/1e6;
         long[] m=procMem();
         var st=cached.getCacheStats();
         // CacheStats API varies slightly across Tileverse versions, so emit its stable
         // string form as the final field and keep direct cache-size methods out: those
         // are package-private in current source.
         System.out.printf(Locale.ROOT,"%s,%s,%d,%s,%d,%.6f,%d,%d,%d,%d,,,,\"%s\"%n",
           "parquetry-cache",layout,pos,q.name,rows,ms,m[0],m[0]-baseRss,m[1],heapUsed(),st.toString().replace("\"","'"));
       }
     }
   }
 }
}
